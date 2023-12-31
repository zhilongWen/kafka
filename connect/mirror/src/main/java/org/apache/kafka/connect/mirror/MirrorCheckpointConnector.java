/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.mirror;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.utils.AppInfoParser;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.source.SourceConnector;
import org.apache.kafka.connect.util.ConnectorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/** Replicate consumer group state between clusters. Emits checkpoint records.
 *
 *  @see MirrorCheckpointConfig for supported config properties.
 */
public class MirrorCheckpointConnector extends SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(MirrorCheckpointConnector.class);

    private Scheduler scheduler;
    private MirrorCheckpointConfig config;
    private TopicFilter topicFilter;
    private GroupFilter groupFilter;
    private Admin sourceAdminClient;
    private Admin targetAdminClient;
    private SourceAndTarget sourceAndTarget;
    private List<String> knownConsumerGroups = Collections.emptyList();

    public MirrorCheckpointConnector() {
        // nop
    }

    // visible for testing
    MirrorCheckpointConnector(List<String> knownConsumerGroups, MirrorCheckpointConfig config) {
        this.knownConsumerGroups = knownConsumerGroups;
        this.config = config;
    }

    @Override
    public void start(Map<String, String> props) {
        config = new MirrorCheckpointConfig(props);
        if (!config.enabled()) {
            return;
        }
        String connectorName = config.connectorName();
        sourceAndTarget = new SourceAndTarget(config.sourceClusterAlias(), config.targetClusterAlias());
        topicFilter = config.topicFilter();
        groupFilter = config.groupFilter();
        sourceAdminClient = config.forwardingAdmin(config.sourceAdminConfig());
        targetAdminClient = config.forwardingAdmin(config.targetAdminConfig());
        scheduler = new Scheduler(MirrorCheckpointConnector.class, config.adminTimeout());
        scheduler.execute(this::createInternalTopics, "creating internal topics");
        scheduler.execute(this::loadInitialConsumerGroups, "loading initial consumer groups");
        scheduler.scheduleRepeatingDelayed(this::refreshConsumerGroups, config.refreshGroupsInterval(),
                "refreshing consumer groups");
        log.info("Started {} with {} consumer groups.", connectorName, knownConsumerGroups.size());
        log.debug("Started {} with consumer groups: {}", connectorName, knownConsumerGroups);
    }

    @Override
    public void stop() {
        if (!config.enabled()) {
            return;
        }
        Utils.closeQuietly(scheduler, "scheduler");
        Utils.closeQuietly(topicFilter, "topic filter");
        Utils.closeQuietly(groupFilter, "group filter");
        Utils.closeQuietly(sourceAdminClient, "source admin client");
        Utils.closeQuietly(targetAdminClient, "target admin client");
    }

    @Override
    public Class<? extends Task> taskClass() {
        return MirrorCheckpointTask.class;
    }

    // divide consumer groups among tasks
    @Override
    public List<Map<String, String>> taskConfigs(int maxTasks) {
        // if the replication is disabled, known consumer group is empty, or checkpoint emission is
        // disabled by setting 'emit.checkpoints.enabled' to false, the interval of checkpoint emission
        // will be negative and no 'MirrorHeartbeatTask' will be created
        if (!config.enabled() || knownConsumerGroups.isEmpty()
                || config.emitCheckpointsInterval().isNegative()) {
            return Collections.emptyList();
        }
        int numTasks = Math.min(maxTasks, knownConsumerGroups.size());
        return ConnectorUtils.groupPartitions(knownConsumerGroups, numTasks).stream()
                .map(config::taskConfigForConsumerGroups)
                .collect(Collectors.toList());
    }

    @Override
    public ConfigDef config() {
        return MirrorCheckpointConfig.CONNECTOR_CONFIG_DEF;
    }

    @Override
    public String version() {
        return AppInfoParser.getVersion();
    }

    private void refreshConsumerGroups()
            throws InterruptedException, ExecutionException {
        List<String> consumerGroups = findConsumerGroups();
        Set<String> newConsumerGroups = new HashSet<>();
        newConsumerGroups.addAll(consumerGroups);
        newConsumerGroups.removeAll(knownConsumerGroups);
        Set<String> deadConsumerGroups = new HashSet<>();
        deadConsumerGroups.addAll(knownConsumerGroups);
        deadConsumerGroups.removeAll(consumerGroups);
        if (!newConsumerGroups.isEmpty() || !deadConsumerGroups.isEmpty()) {
            log.info("Found {} consumer groups for {}. {} are new. {} were removed. Previously had {}.",
                    consumerGroups.size(), sourceAndTarget, newConsumerGroups.size(), deadConsumerGroups.size(),
                    knownConsumerGroups.size());
            log.debug("Found new consumer groups: {}", newConsumerGroups);
            knownConsumerGroups = consumerGroups;
            context.requestTaskReconfiguration();
        }
    }

    private void loadInitialConsumerGroups()
            throws InterruptedException, ExecutionException {
        knownConsumerGroups = findConsumerGroups();
    }

    List<String> findConsumerGroups()
            throws InterruptedException, ExecutionException {
        List<String> filteredGroups = listConsumerGroups().stream()
                .map(ConsumerGroupListing::groupId)
                .filter(this::shouldReplicateByGroupFilter)
                .collect(Collectors.toList());

        List<String> checkpointGroups = new LinkedList<>();
        List<String> irrelevantGroups = new LinkedList<>();

        for (String group : filteredGroups) {
            Set<String> consumedTopics = listConsumerGroupOffsets(group).keySet().stream()
                    .map(TopicPartition::topic)
                    .filter(this::shouldReplicateByTopicFilter)
                    .collect(Collectors.toSet());
            // Only perform checkpoints for groups that have offsets for at least one topic that's accepted
            // by the topic filter.
            if (consumedTopics.size() > 0) {
                checkpointGroups.add(group);
            } else {
                irrelevantGroups.add(group);
            }
        }

        log.debug("Ignoring the following groups which do not have any offsets for topics that are accepted by " +
                        "the topic filter: {}", irrelevantGroups);
        return checkpointGroups;
    }

    Collection<ConsumerGroupListing> listConsumerGroups()
            throws InterruptedException, ExecutionException {
        return sourceAdminClient.listConsumerGroups().valid().get();
    }

    private void createInternalTopics() {
        MirrorUtils.createSinglePartitionCompactedTopic(
                config.checkpointsTopic(),
                config.checkpointsTopicReplicationFactor(),
                targetAdminClient
        );
    }

    Map<TopicPartition, OffsetAndMetadata> listConsumerGroupOffsets(String group)
            throws InterruptedException, ExecutionException {
        return sourceAdminClient.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
    }

    boolean shouldReplicateByGroupFilter(String group) {
        return groupFilter.shouldReplicateGroup(group);
    }

    boolean shouldReplicateByTopicFilter(String topic) {
        return topicFilter.shouldReplicateTopic(topic);
    }
}
