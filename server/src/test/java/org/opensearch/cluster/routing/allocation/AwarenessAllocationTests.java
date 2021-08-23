/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.cluster.routing.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.Version;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.OpenSearchAllocationTestCase;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.routing.UnassignedInfo;
import org.opensearch.cluster.routing.allocation.command.AllocationCommands;
import org.opensearch.cluster.routing.allocation.command.CancelAllocationCommand;
import org.opensearch.cluster.routing.allocation.command.MoveAllocationCommand;
import org.opensearch.cluster.routing.allocation.decider.AwarenessAllocationDecider;
import org.opensearch.cluster.routing.allocation.decider.ClusterRebalanceAllocationDecider;
import org.opensearch.cluster.routing.allocation.decider.ThrottlingAllocationDecider;
import org.opensearch.common.settings.Settings;

import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.singletonMap;
import static org.opensearch.cluster.routing.ShardRoutingState.INITIALIZING;
import static org.opensearch.cluster.routing.ShardRoutingState.RELOCATING;
import static org.opensearch.cluster.routing.ShardRoutingState.STARTED;
import static org.opensearch.cluster.routing.ShardRoutingState.UNASSIGNED;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.sameInstance;

public class AwarenessAllocationTests extends OpenSearchAllocationTestCase {

    private final Logger logger = LogManager.getLogger(AwarenessAllocationTests.class);

    public void testMoveShardOnceNewNodeWithAttributeAdded1() {
        AllocationService strategy = createAllocationService(Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_REPLICAS_RECOVERIES_SETTING.getKey(), 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
                .put("cluster.routing.allocation.awareness.attributes", "rack_id")
                .build());

        logger.info("Building initial routing table for 'moveShardOnceNewNodeWithAttributeAdded1'");

        Metadata metadata = Metadata.builder()
                .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
                .build();

        RoutingTable initialRoutingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();

        ClusterState clusterState = ClusterState.builder(org.opensearch.cluster.ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding two nodes on same rack and do rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                .add(newNode("node1", singletonMap("rack_id", "1")))
                .add(newNode("node2", singletonMap("rack_id", "1")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(1));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> start the shards (replicas)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(2));

        logger.info("--> add a new node with a new rack and reroute");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node3", singletonMap("rack_id", "2")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.RELOCATING).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.RELOCATING).get(0).relocatingNodeId(),
            equalTo("node3"));

        logger.info("--> complete relocation");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(2));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));

        logger.info("--> add another node with a new rack, make sure nothing moves");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node4", singletonMap("rack_id", "3")))
        ).build();
        ClusterState newState = strategy.reroute(clusterState, "reroute");
        assertThat(newState, equalTo(clusterState));
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(2));
    }

    public void testMoveShardOnceNewNodeWithAttributeAdded2() {
        AllocationService strategy = createAllocationService(Settings.builder()
                .put("cluster.routing.allocation.concurrent_recoveries", 10)
                .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_REPLICAS_RECOVERIES_SETTING.getKey(), 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
                .put("cluster.routing.allocation.awareness.attributes", "rack_id")
                .build());

        logger.info("Building initial routing table for 'moveShardOnceNewNodeWithAttributeAdded2'");

        Metadata metadata = Metadata.builder()
                .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
                .build();

        RoutingTable initialRoutingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();

        ClusterState clusterState = ClusterState.builder(org.opensearch.cluster.ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding two nodes on same rack and do rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                .add(newNode("node1", singletonMap("rack_id", "1")))
                .add(newNode("node2", singletonMap("rack_id", "1")))
                .add(newNode("node3", singletonMap("rack_id", "1")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(1));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> start the shards (replicas)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(2));

        logger.info("--> add a new node with a new rack and reroute");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node4", singletonMap("rack_id", "2")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.RELOCATING).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.RELOCATING).get(0).relocatingNodeId(),
            equalTo("node4"));

        logger.info("--> complete relocation");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(2));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));

        logger.info("--> add another node with a new rack, make sure nothing moves");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node5", singletonMap("rack_id", "3")))
        ).build();
        ClusterState newState = strategy.reroute(clusterState, "reroute");
        assertThat(newState, equalTo(clusterState));
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(2));
    }

    public void testMoveShardOnceNewNodeWithAttributeAdded3() {
        AllocationService strategy = createAllocationService(Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put("cluster.routing.allocation.node_initial_primaries_recoveries", 10)
                .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_REPLICAS_RECOVERIES_SETTING.getKey(), 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1)
                .put("cluster.routing.allocation.awareness.attributes", "rack_id")
                .put("cluster.routing.allocation.balance.index", 0.0f)
                .put("cluster.routing.allocation.balance.replica", 1.0f)
                .put("cluster.routing.allocation.balance.primary", 0.0f)
                .build());

        logger.info("Building initial routing table for 'moveShardOnceNewNodeWithAttributeAdded3'");

        Metadata metadata = Metadata.builder()
                .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(5).numberOfReplicas(1))
                .build();

        RoutingTable initialRoutingTable = RoutingTable.builder()
                .addAsNew(metadata.index("test"))
                .build();

        ClusterState clusterState = ClusterState.builder(org.opensearch.cluster.ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding two nodes on same rack and do rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                .add(newNode("node1", singletonMap("rack_id", "1")))
                .add(newNode("node2", singletonMap("rack_id", "1")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        logger.info("Initializing shards: {}", clusterState.getRoutingNodes().shardsWithState(INITIALIZING));
        logger.info("Started shards: {}", clusterState.getRoutingNodes().shardsWithState(STARTED));
        logger.info("Relocating shards: {}", clusterState.getRoutingNodes().shardsWithState(RELOCATING));
        logger.info("Unassigned shards: {}", clusterState.getRoutingNodes().shardsWithState(UNASSIGNED));

        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(5));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> start the shards (replicas)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(10));

        logger.info("--> add a new node with a new rack and reroute");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node3", singletonMap("rack_id", "2")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(5));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.RELOCATING).size(), equalTo(5));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).size(), equalTo(5));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.RELOCATING).get(0).relocatingNodeId(),
            equalTo("node3"));

        logger.info("--> complete initializing");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> run it again, since we still might have relocation");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(10));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));

        logger.info("--> add another node with a new rack, some more relocation should happen");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node4", singletonMap("rack_id", "3")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(RELOCATING).size(), greaterThan(0));

        logger.info("--> complete relocation");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(10));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));
    }

    public void testMoveShardOnceNewNodeWithAttributeAdded4() {
        AllocationService strategy = createAllocationService(Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put("cluster.routing.allocation.node_initial_primaries_recoveries", 10)
                .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_REPLICAS_RECOVERIES_SETTING.getKey(), 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1)
                .put("cluster.routing.allocation.awareness.attributes", "rack_id")
                .build());

        logger.info("Building initial routing table for 'moveShardOnceNewNodeWithAttributeAdded4'");

        Metadata metadata = Metadata.builder()
                .put(IndexMetadata.builder("test1").settings(settings(Version.CURRENT)).numberOfShards(5).numberOfReplicas(1))
                .put(IndexMetadata.builder("test2").settings(settings(Version.CURRENT)).numberOfShards(5).numberOfReplicas(1))
                .build();

        RoutingTable initialRoutingTable = RoutingTable.builder()
                .addAsNew(metadata.index("test1"))
                .addAsNew(metadata.index("test2"))
                .build();

        ClusterState clusterState = ClusterState.builder(org.opensearch.cluster.ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding two nodes on same rack and do rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                .add(newNode("node1", singletonMap("rack_id", "1")))
                .add(newNode("node2", singletonMap("rack_id", "1")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(10));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> start the shards (replicas)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(20));

        logger.info("--> add a new node with a new rack and reroute");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node3", singletonMap("rack_id", "2")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(10));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.RELOCATING).size(), equalTo(10));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).size(), equalTo(10));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.RELOCATING).get(0).relocatingNodeId(),
            equalTo("node3"));

        logger.info("--> complete initializing");
        for (int i = 0; i < 2; i++) {
            logger.info("--> complete initializing round: [{}]", i);
            clusterState = startInitializingShardsAndReroute(strategy, clusterState);
        }
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(20));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(10));
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(5));
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(5));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));

        logger.info("--> add another node with a new rack, some more relocation should happen");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node4", singletonMap("rack_id", "3")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(RELOCATING).size(), greaterThan(0));

        logger.info("--> complete relocation");
        for (int i = 0; i < 2; i++) {
            logger.info("--> complete initializing round: [{}]", i);
            clusterState = startInitializingShardsAndReroute(strategy, clusterState);
        }
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(20));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(5));
        assertThat(clusterState.getRoutingNodes().node("node4").size(), equalTo(5));
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(5));
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(5));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));
    }

    public void testMoveShardOnceNewNodeWithAttributeAdded5() {
        AllocationService strategy = createAllocationService(Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_REPLICAS_RECOVERIES_SETTING.getKey(), 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
                .put("cluster.routing.allocation.awareness.attributes", "rack_id")
                .build());

        logger.info("Building initial routing table for 'moveShardOnceNewNodeWithAttributeAdded5'");

        Metadata metadata = Metadata.builder()
                .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(2))
                .build();

        RoutingTable initialRoutingTable = RoutingTable.builder()
                .addAsNew(metadata.index("test"))
                .build();

        ClusterState clusterState = ClusterState.builder(org.opensearch.cluster.ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding two nodes on same rack and do rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                .add(newNode("node1", singletonMap("rack_id", "1")))
                .add(newNode("node2", singletonMap("rack_id", "1")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(1));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> start the shards (replicas)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(2));

        logger.info("--> add a new node with a new rack and reroute");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node3", singletonMap("rack_id", "2")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(2));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).get(0).currentNodeId(),
            equalTo("node3"));

        logger.info("--> complete relocation");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(3));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));

        logger.info("--> add another node with a new rack, we will have another relocation");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node4", singletonMap("rack_id", "3")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(2));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.RELOCATING).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.RELOCATING).get(0).relocatingNodeId(),
            equalTo("node4"));

        logger.info("--> complete relocation");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(3));

        logger.info("--> make sure another reroute does not move things");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));
    }

    public void testMoveShardOnceNewNodeWithAttributeAdded6() {
        AllocationService strategy = createAllocationService(Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_REPLICAS_RECOVERIES_SETTING.getKey(), 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
                .put("cluster.routing.allocation.awareness.attributes", "rack_id")
                .build());

        logger.info("Building initial routing table for 'moveShardOnceNewNodeWithAttributeAdded6'");

        Metadata metadata = Metadata.builder()
                .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(3))
                .build();

        RoutingTable initialRoutingTable = RoutingTable.builder()
                .addAsNew(metadata.index("test"))
                .build();

        ClusterState clusterState = ClusterState.builder(org.opensearch.cluster.ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding two nodes on same rack and do rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                .add(newNode("node1", singletonMap("rack_id", "1")))
                .add(newNode("node2", singletonMap("rack_id", "1")))
                .add(newNode("node3", singletonMap("rack_id", "1")))
                .add(newNode("node4", singletonMap("rack_id", "1")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(1));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> start the shards (replicas)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(4));

        logger.info("--> add a new node with a new rack and reroute");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node5", singletonMap("rack_id", "2")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(3));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.RELOCATING).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.RELOCATING).get(0).relocatingNodeId(),
            equalTo("node5"));

        logger.info("--> complete relocation");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(4));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));

        logger.info("--> add another node with a new rack, we will have another relocation");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node6", singletonMap("rack_id", "3")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(3));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.RELOCATING).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.RELOCATING).get(0).relocatingNodeId(),
            equalTo("node6"));

        logger.info("--> complete relocation");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(4));

        logger.info("--> make sure another reroute does not move things");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));
    }

    public void testFullAwareness1() {
        AllocationService strategy = createAllocationService(Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_REPLICAS_RECOVERIES_SETTING.getKey(), 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
                .put("cluster.routing.allocation.awareness.force.rack_id.values", "1,2")
                .put("cluster.routing.allocation.awareness.attributes", "rack_id")
                .build());

        logger.info("Building initial routing table for 'fullAwareness1'");

        Metadata metadata = Metadata.builder()
                .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
                .build();

        RoutingTable initialRoutingTable = RoutingTable.builder()
                .addAsNew(metadata.index("test"))
                .build();

        ClusterState clusterState = ClusterState.builder(org.opensearch.cluster.ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding two nodes on same rack and do rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                .add(newNode("node1", singletonMap("rack_id", "1")))
                .add(newNode("node2", singletonMap("rack_id", "1")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(1));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> replica will not start because we have only one rack value");
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(0));

        logger.info("--> add a new node with a new rack and reroute");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node3", singletonMap("rack_id", "2")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).get(0).currentNodeId(),
            equalTo("node3"));

        logger.info("--> complete relocation");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(2));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));

        logger.info("--> add another node with a new rack, make sure nothing moves");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node4", singletonMap("rack_id", "3")))
        ).build();
        ClusterState newState = strategy.reroute(clusterState, "reroute");
        assertThat(newState, equalTo(clusterState));
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(2));
    }

    public void testFullAwareness2() {
        AllocationService strategy = createAllocationService(Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_REPLICAS_RECOVERIES_SETTING.getKey(), 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
                .put("cluster.routing.allocation.awareness.force.rack_id.values", "1,2")
                .put("cluster.routing.allocation.awareness.attributes", "rack_id")
                .build());

        logger.info("Building initial routing table for 'fullAwareness2'");

        Metadata metadata = Metadata.builder()
                .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
                .build();

        RoutingTable initialRoutingTable = RoutingTable.builder()
                .addAsNew(metadata.index("test"))
                .build();

        ClusterState clusterState = ClusterState.builder(org.opensearch.cluster.ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding two nodes on same rack and do rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                .add(newNode("node1", singletonMap("rack_id", "1")))
                .add(newNode("node2", singletonMap("rack_id", "1")))
                .add(newNode("node3", singletonMap("rack_id", "1")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(1));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> replica will not start because we have only one rack value");
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(0));

        logger.info("--> add a new node with a new rack and reroute");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node4", singletonMap("rack_id", "2")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).get(0).currentNodeId(),
            equalTo("node4"));

        logger.info("--> complete relocation");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(2));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));

        logger.info("--> add another node with a new rack, make sure nothing moves");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node5", singletonMap("rack_id", "3")))
        ).build();
        ClusterState newState = strategy.reroute(clusterState, "reroute");
        assertThat(newState, equalTo(clusterState));
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(2));
    }

    public void testFullAwareness3() {
        AllocationService strategy = createAllocationService(Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put("cluster.routing.allocation.node_initial_primaries_recoveries", 10)
                .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_REPLICAS_RECOVERIES_SETTING.getKey(), 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1)
                .put("cluster.routing.allocation.awareness.force.rack_id.values", "1,2")
                .put("cluster.routing.allocation.awareness.attributes", "rack_id")
                .put("cluster.routing.allocation.balance.index", 0.0f)
                .put("cluster.routing.allocation.balance.replica", 1.0f)
                .put("cluster.routing.allocation.balance.primary", 0.0f)
                .build());

        logger.info("Building initial routing table for 'fullAwareness3'");

        Metadata metadata = Metadata.builder()
                .put(IndexMetadata.builder("test1").settings(settings(Version.CURRENT)).numberOfShards(5).numberOfReplicas(1))
                .put(IndexMetadata.builder("test2").settings(settings(Version.CURRENT)).numberOfShards(5).numberOfReplicas(1))
                .build();

        RoutingTable initialRoutingTable = RoutingTable.builder()
                .addAsNew(metadata.index("test1"))
                .addAsNew(metadata.index("test2"))
                .build();

        ClusterState clusterState = ClusterState.builder(org.opensearch.cluster.ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding two nodes on same rack and do rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                .add(newNode("node1", singletonMap("rack_id", "1")))
                .add(newNode("node2", singletonMap("rack_id", "1")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(10));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(10));

        logger.info("--> add a new node with a new rack and reroute");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node3", singletonMap("rack_id", "2")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(10));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).size(), equalTo(10));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).get(0).currentNodeId(),
            equalTo("node3"));

        logger.info("--> complete initializing");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> run it again, since we still might have relocation");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(20));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));

        logger.info("--> add another node with a new rack, some more relocation should happen");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("node4", singletonMap("rack_id", "3")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(RELOCATING).size(), greaterThan(0));

        logger.info("--> complete relocation");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(20));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));
    }

    public void testUnbalancedZones() {
        AllocationService strategy = createAllocationService(Settings.builder()
                .put("cluster.routing.allocation.awareness.force.zone.values", "a,b")
                .put("cluster.routing.allocation.awareness.attributes", "zone")
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_REPLICAS_RECOVERIES_SETTING.getKey(), 10)
                .put("cluster.routing.allocation.node_initial_primaries_recoveries", 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1)
                .build());

        logger.info("Building initial routing table for 'testUnbalancedZones'");

        Metadata metadata = Metadata.builder()
                .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(5).numberOfReplicas(1))
                .build();

        RoutingTable initialRoutingTable = RoutingTable.builder()
                .addAsNew(metadata.index("test"))
                .build();

        ClusterState clusterState = ClusterState.builder(org.opensearch.cluster.ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding two nodes in different zones and do rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                .add(newNode("A-0", singletonMap("zone", "a")))
                .add(newNode("B-0", singletonMap("zone", "b")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(0));
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(5));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(5));
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(5));

        clusterState = startInitializingShardsAndReroute(strategy, clusterState);
        logger.info("--> all replicas are allocated and started since we have on node in each zone");
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(10));
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(0));

        logger.info("--> add a new node in zone 'a' and reroute");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .add(newNode("A-1", singletonMap("zone", "a")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(8));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).size(), equalTo(2));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).get(0).currentNodeId(),
            equalTo("A-1"));
        logger.info("--> starting initializing shards on the new node");

        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(10));
        assertThat(clusterState.getRoutingNodes().node("A-1").size(), equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("A-0").size(), equalTo(3));
        assertThat(clusterState.getRoutingNodes().node("B-0").size(), equalTo(5));
    }

    public void testUnassignedShardsWithUnbalancedZones() {
        AllocationService strategy = createAllocationService(Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_REPLICAS_RECOVERIES_SETTING.getKey(), 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
                .put("cluster.routing.allocation.awareness.attributes", "zone")
                .build());

        logger.info("Building initial routing table for 'testUnassignedShardsWithUnbalancedZones'");

        Metadata metadata = Metadata.builder()
                .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(4))
                .build();

        RoutingTable initialRoutingTable = RoutingTable.builder()
                .addAsNew(metadata.index("test"))
                .build();

        ClusterState clusterState = ClusterState.builder(org.opensearch.cluster.ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding 5 nodes in different zones and do rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                        .add(newNode("A-0", singletonMap("zone", "a")))
                        .add(newNode("A-1", singletonMap("zone", "a")))
                        .add(newNode("A-2", singletonMap("zone", "a")))
                        .add(newNode("A-3", singletonMap("zone", "a")))
                        .add(newNode("A-4", singletonMap("zone", "a")))
                        .add(newNode("B-0", singletonMap("zone", "b")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(0));
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(1));

        logger.info("--> start the shard (primary)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(3));
        assertThat(clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).size(), equalTo(1)); // Unassigned shard is expected.

        // Cancel all initializing shards and move started primary to another node.
        AllocationCommands commands = new AllocationCommands();
        String primaryNode = null;
        for (ShardRouting routing : clusterState.routingTable().allShards()) {
            if (routing.primary()) {
                primaryNode = routing.currentNodeId();
            } else if (routing.initializing()) {
                commands.add(new CancelAllocationCommand(routing.shardId().getIndexName(), routing.id(), routing.currentNodeId(), false));
            }
        }
        commands.add(new MoveAllocationCommand("test", 0, primaryNode, "A-4"));

        clusterState = strategy.reroute(clusterState, commands, false, false).getClusterState();

        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(0));
        assertThat(clusterState.getRoutingNodes().shardsWithState(RELOCATING).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(4)); // +1 for relocating shard.
        assertThat(clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).size(), equalTo(1)); // Still 1 unassigned.
    }

    public void testMultipleAwarenessAttributes() {
        AllocationService strategy = createAllocationService(Settings.builder()
            .put(AwarenessAllocationDecider.CLUSTER_ROUTING_ALLOCATION_AWARENESS_ATTRIBUTE_SETTING.getKey(), "zone, rack")
            .put(AwarenessAllocationDecider.CLUSTER_ROUTING_ALLOCATION_AWARENESS_FORCE_GROUP_SETTING.getKey() + "zone.values", "a, b")
            .put(AwarenessAllocationDecider.CLUSTER_ROUTING_ALLOCATION_AWARENESS_FORCE_GROUP_SETTING.getKey() + "rack.values", "c, d")
            .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
            .build());

        logger.info("Building initial routing table for 'testUnbalancedZones'");

        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
            .build();

        RoutingTable initialRoutingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();

        ClusterState clusterState = ClusterState.builder(
            org.opensearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)
        ).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding two nodes in different zones and do rerouting");
        Map<String, String> nodeAAttributes = new HashMap<>();
        nodeAAttributes.put("zone", "a");
        nodeAAttributes.put("rack", "c");
        Map<String, String> nodeBAttributes = new HashMap<>();
        nodeBAttributes.put("zone", "b");
        nodeBAttributes.put("rack", "d");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
            .add(newNode("A-0", nodeAAttributes))
            .add(newNode("B-0", nodeBAttributes))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(0));
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(1));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(1));

        clusterState = startInitializingShardsAndReroute(strategy, clusterState);
        logger.info("--> all replicas are allocated and started since we have one node in each zone and rack");
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(2));
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(0));
    }

    public void testDisabledForcedAllocationPreventsOverload() {
        AllocationService strategy = createAllocationService(Settings.builder()
            .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_CONCURRENT_RECOVERIES_SETTING.getKey(), 21)
            .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_PRIMARIES_RECOVERIES_SETTING.getKey(), 21)
            .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_REPLICAS_RECOVERIES_SETTING.getKey(), 21)
            .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
            .put(AwarenessAllocationDecider.CLUSTER_ROUTING_ALLOCATION_AWARENESS_FORCED_ALLOCATION_DISABLE_SETTING.getKey(), true)
            .put("cluster.routing.allocation.awareness.attribute.zone.capacity", 3)
            .put("cluster.routing.allocation.awareness.force.zone.values", "zone_1,zone_2,zone_3")
            .put("cluster.routing.allocation.awareness.attributes", "zone")
            .put(AwarenessAllocationDecider.CLUSTER_ROUTING_ALLOCATION_AWARENESS_SKEWNESS_LIMIT.getKey(), 0)
            .build());

        logger.info("Building initial routing table for 'fullAwareness1'");

        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(21).numberOfReplicas(2))
            .build();

        RoutingTable initialRoutingTable = RoutingTable.builder()
            .addAsNew(metadata.index("test"))
            .build();

        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding three nodes on same zone and do rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
            .add(newNode("node1", singletonMap("zone", "zone_1")))
            .add(newNode("node2", singletonMap("zone", "zone_1")))
            .add(newNode("node3", singletonMap("zone", "zone_1")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(21));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> replica will not start because we have only one rack value");
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(21));
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(0));
        //replicas are unassigned
        assertThat(clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).size(), equalTo(42));

        logger.info("--> add three new node with a new rack and reroute");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
            .add(newNode("node4", singletonMap("zone", "zone_2")))
            .add(newNode("node5", singletonMap("zone", "zone_2")))
            .add(newNode("node6", singletonMap("zone", "zone_2")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(21));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).size(), equalTo(21));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).get(0).currentNodeId(),
            equalTo("node4"));

        logger.info("--> complete relocation");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(42));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));

        logger.info("--> add another node with a new rack, make sure nothing moves");

        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
            .add(newNode("node7", singletonMap("zone", "zone_3")))
            .add(newNode("node8", singletonMap("zone", "zone_3")))
            .add(newNode("node9", singletonMap("zone", "zone_3")))
        ).build();
        ClusterState newState = strategy.reroute(clusterState, "reroute");
        while (newState.getRoutingNodes().shardsWithState(INITIALIZING).isEmpty() == false) {
            newState = startInitializingShardsAndReroute(strategy, newState);
        }
        assertThat(newState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(63));

        logger.info("--> Remove random node from zones holding all primary and all replicas");
        //remove two nodes in one zone to cause distribution zone1->3 , zone2->3, zone3->1
        newState = removeNode(newState, randomFrom("node1", "node7" ), strategy);
        logger.info("--> Remove another random node from zones holding all primary and all replicas");
        newState = removeNode(newState, randomFrom("node2", "node8" ), strategy);
        newState = strategy.reroute(newState, "reroute");
        while (newState.getRoutingNodes().shardsWithState(INITIALIZING).isEmpty() == false) {
            newState = startInitializingShardsAndReroute(strategy, newState);
        }
        //ensure minority zone doesn't get overloaded
        assertThat(newState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(49));
        assertThat(newState.getRoutingNodes().shardsWithState(UNASSIGNED).size(), equalTo(14));
        for (ShardRouting shard : newState.getRoutingNodes().shardsWithState(UNASSIGNED)) {
            assertEquals(shard.unassignedInfo().getReason(), UnassignedInfo.Reason.NODE_LEFT);
        }
    }

    private ClusterState removeNode(ClusterState clusterState, String nodeName, AllocationService allocationService) {
        return allocationService.disassociateDeadNodes(ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder(clusterState.getNodes()).remove(nodeName)).build(), true, "reroute");
    }


    public void testMoveShardDuringPartialFailureSkewnessLimitNotBreached(){
        AllocationService strategy = createAllocationService(Settings.builder()
            .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_CONCURRENT_RECOVERIES_SETTING.getKey(), 20)
            .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_PRIMARIES_RECOVERIES_SETTING.getKey(), 20)
            .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_REPLICAS_RECOVERIES_SETTING.getKey(), 20)
            .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
            .put(AwarenessAllocationDecider.CLUSTER_ROUTING_ALLOCATION_AWARENESS_FORCED_ALLOCATION_DISABLE_SETTING.getKey(), true)
            .put("cluster.routing.allocation.awareness.attribute.zone.capacity", 5)
            .put("cluster.routing.allocation.awareness.force.zone.values", "zone1,zone2,zone3")
            .put("cluster.routing.allocation.awareness.attributes", "zone")
            .put(AwarenessAllocationDecider.CLUSTER_ROUTING_ALLOCATION_AWARENESS_SKEWNESS_LIMIT.getKey(), 20)
            .build());

        logger.info("Building initial routing table for 'testMoveShardDuringPartialFailureSkewnessLimitNotBreached'");

        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(20).numberOfReplicas(2))
            .build();

        RoutingTable initialRoutingTable = RoutingTable.builder()
            .addAsNew(metadata.index("test"))
            .build();

        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding five nodes on same zone and do rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
            .add(newNode("node1", singletonMap("zone", "zone1")))
            .add(newNode("node2", singletonMap("zone", "zone1")))
            .add(newNode("node3", singletonMap("zone", "zone1")))
            .add(newNode("node4", singletonMap("zone", "zone1")))
            .add(newNode("node5", singletonMap("zone", "zone1")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(20));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> replica will not start because we have only one zone value");
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(20));
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(0));
        //replicas are unassigned
        assertThat(clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).size(), equalTo(40));

        logger.info("--> add five new node in new zone and reroute");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
            .add(newNode("node6", singletonMap("zone", "zone2")))
            .add(newNode("node7", singletonMap("zone", "zone2")))
            .add(newNode("node8", singletonMap("zone", "zone2")))
            .add(newNode("node9", singletonMap("zone", "zone2")))
            .add(newNode("node10", singletonMap("zone", "zone2")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(20));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).size(), equalTo(20));

        logger.info("--> complete relocation");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(40));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));

        logger.info("--> add another five node in new zone and reroute");

        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
            .add(newNode("node11", singletonMap("zone", "zone3")))
            .add(newNode("node12", singletonMap("zone", "zone3")))
            .add(newNode("node13", singletonMap("zone", "zone3")))
            .add(newNode("node14", singletonMap("zone", "zone3")))
            .add(newNode("node15", singletonMap("zone", "zone3")))
        ).build();
        ClusterState newState = strategy.reroute(clusterState, "reroute");
        while (newState.getRoutingNodes().shardsWithState(INITIALIZING).isEmpty() == false) {
            newState = startInitializingShardsAndReroute(strategy, newState);
        }
        assertThat(newState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(60));

        logger.info("--> Remove one node from zone3 holding all primary and all replicas");

        assertThat(newState.getRoutingNodes().node("node11").size(), equalTo(4));
        assertThat(newState.getRoutingNodes().node("node12").size(), equalTo(4));
        assertThat(newState.getRoutingNodes().node("node13").size(), equalTo(4));
        assertThat(newState.getRoutingNodes().node("node14").size(), equalTo(4));
        assertThat(newState.getRoutingNodes().node("node15").size(), equalTo(4));

//        remove one nodes in one zone to cause distribution zone1->5 , zone2->5, zone3->4
        newState = removeNode(newState, "node11", strategy);
        newState = strategy.reroute(newState, "reroute");

        while (newState.getRoutingNodes().shardsWithState(INITIALIZING).isEmpty() == false) {
            newState = startInitializingShardsAndReroute(strategy, newState);
        }

        assertThat(newState.getRoutingNodes().node("node12").size(), equalTo(5));
        assertThat(newState.getRoutingNodes().node("node13").size(), equalTo(5));
        assertThat(newState.getRoutingNodes().node("node14").size(), equalTo(5));
        assertThat(newState.getRoutingNodes().node("node15").size(), equalTo(5));

//        //ensure all shards are assigned
        assertThat(newState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(60));
        assertThat(newState.getRoutingNodes().shardsWithState(UNASSIGNED).size(), equalTo(0));
    }

    public void testShardUnassignedDuringPartialFailureSkewnessLimitBreached(){
        AllocationService strategy = createAllocationService(Settings.builder()
            .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_CONCURRENT_RECOVERIES_SETTING.getKey(), 20)
            .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_PRIMARIES_RECOVERIES_SETTING.getKey(), 20)
            .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_REPLICAS_RECOVERIES_SETTING.getKey(), 20)
            .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
            .put(AwarenessAllocationDecider.CLUSTER_ROUTING_ALLOCATION_AWARENESS_FORCED_ALLOCATION_DISABLE_SETTING.getKey(), true)
            .put("cluster.routing.allocation.awareness.attribute.zone.capacity", 5)
            .put("cluster.routing.allocation.awareness.force.zone.values", "zone1,zone2,zone3")
            .put("cluster.routing.allocation.awareness.attributes", "zone")
            .put(AwarenessAllocationDecider.CLUSTER_ROUTING_ALLOCATION_AWARENESS_SKEWNESS_LIMIT.getKey(), 20)
            .build());

        logger.info("Building initial routing table for 'testShardUnassignedDuringPartialFailureSkewnessLimitBreached'");

        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(20).numberOfReplicas(2))
            .build();

        RoutingTable initialRoutingTable = RoutingTable.builder()
            .addAsNew(metadata.index("test"))
            .build();

        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding five nodes on same zone and do rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
            .add(newNode("node1", singletonMap("zone", "zone1")))
            .add(newNode("node2", singletonMap("zone", "zone1")))
            .add(newNode("node3", singletonMap("zone", "zone1")))
            .add(newNode("node4", singletonMap("zone", "zone1")))
            .add(newNode("node5", singletonMap("zone", "zone1")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(20));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> replica will not start because we have only one zone value");
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(20));
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(0));
        //replicas are unassigned
        assertThat(clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).size(), equalTo(40));

        logger.info("--> add five new node in new zone and reroute");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
            .add(newNode("node6", singletonMap("zone", "zone2")))
            .add(newNode("node7", singletonMap("zone", "zone2")))
            .add(newNode("node8", singletonMap("zone", "zone2")))
            .add(newNode("node9", singletonMap("zone", "zone2")))
            .add(newNode("node10", singletonMap("zone", "zone2")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(20));
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).size(), equalTo(20));

        logger.info("--> complete relocation");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(40));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));

        logger.info("--> add another five node in new zone and reroute");

        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
            .add(newNode("node11", singletonMap("zone", "zone3")))
            .add(newNode("node12", singletonMap("zone", "zone3")))
            .add(newNode("node13", singletonMap("zone", "zone3")))
            .add(newNode("node14", singletonMap("zone", "zone3")))
            .add(newNode("node15", singletonMap("zone", "zone3")))
        ).build();
        ClusterState newState = strategy.reroute(clusterState, "reroute");
        while (newState.getRoutingNodes().shardsWithState(INITIALIZING).isEmpty() == false) {
            newState = startInitializingShardsAndReroute(strategy, newState);
        }
        assertThat(newState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(60));

        logger.info("--> Remove one node from zone3 holding all primary and all replicas");

        assertThat(newState.getRoutingNodes().node("node11").size(), equalTo(4));
        assertThat(newState.getRoutingNodes().node("node12").size(), equalTo(4));
        assertThat(newState.getRoutingNodes().node("node13").size(), equalTo(4));
        assertThat(newState.getRoutingNodes().node("node14").size(), equalTo(4));
        assertThat(newState.getRoutingNodes().node("node15").size(), equalTo(4));

        // remove one nodes in one zone to cause distribution zone1->5 , zone2->5, zone3->4
        newState = removeNode(newState, "node11", strategy);
        newState = strategy.reroute(newState, "reroute");

        while (newState.getRoutingNodes().shardsWithState(INITIALIZING).isEmpty() == false) {
            newState = startInitializingShardsAndReroute(strategy, newState);
        }

        assertThat(newState.getRoutingNodes().node("node12").size(), equalTo(5));
        assertThat(newState.getRoutingNodes().node("node13").size(), equalTo(5));
        assertThat(newState.getRoutingNodes().node("node14").size(), equalTo(5));
        assertThat(newState.getRoutingNodes().node("node15").size(), equalTo(5));

        // ensure all shards are assigned
        assertThat(newState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(60));
        assertThat(newState.getRoutingNodes().shardsWithState(UNASSIGNED).size(), equalTo(0));

        // remove one more node subsequently in one zone to cause distribution zone1->5 , zone2->5, zone3->3
        newState = removeNode(newState, "node12", strategy);
        newState = strategy.reroute(newState, "reroute");

        assertThat(newState.getRoutingNodes().node("node13").size(), equalTo(5));
        assertThat(newState.getRoutingNodes().node("node14").size(), equalTo(5));
        assertThat(newState.getRoutingNodes().node("node15").size(), equalTo(5));

        assertThat(newState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(55));
        assertThat(newState.getRoutingNodes().shardsWithState(UNASSIGNED).size(), equalTo(5));

        for (ShardRouting shard : newState.getRoutingNodes().shardsWithState(UNASSIGNED)) {
            assertEquals(shard.unassignedInfo().getReason(), UnassignedInfo.Reason.NODE_LEFT);
        }
    }

    public void testSingleZoneReplicaUnassignedOnSkewnessWithThreeShardCopies() {
        AllocationService strategy = createAllocationService(Settings.builder()
            .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
            .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_REPLICAS_RECOVERIES_SETTING.getKey(), 10)
            .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
            .put(AwarenessAllocationDecider.CLUSTER_ROUTING_ALLOCATION_AWARENESS_FORCED_ALLOCATION_DISABLE_SETTING.getKey(), true)
            .put("cluster.routing.allocation.awareness.attribute.zone.capacity", 3)
            .put("cluster.routing.allocation.awareness.force.zone.values", "zone1")
            .put("cluster.routing.allocation.awareness.attributes", "zone")
            .build());

        logger.info("Building initial routing table for 'testSingleZoneReplicaUnassignedOnSkewnessWithThreeShardCopies'");

        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(3).numberOfReplicas(2))
            .build();

        RoutingTable initialRoutingTable = RoutingTable.builder()
            .addAsNew(metadata.index("test"))
            .build();

        ClusterState clusterState = ClusterState.builder(org.opensearch.cluster.ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding two nodes on same rack and do rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
            .add(newNode("node1", singletonMap("zone", "zone1")))
            .add(newNode("node2", singletonMap("zone", "zone1")))
            .add(newNode("node3", singletonMap("zone", "zone1")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(3));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> replicas are initializing");
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(3));
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(6));

        logger.info("--> start the shards (replicas)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> all shards are started");
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(9));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));

        //remove one node to make zone1 skewed
        clusterState = removeNode(clusterState, randomFrom("node1", "node2", "node3"), strategy);
        clusterState = strategy.reroute(clusterState, "reroute");

        while (clusterState.getRoutingNodes().shardsWithState(INITIALIZING).isEmpty() == false) {
            clusterState = startInitializingShardsAndReroute(strategy, clusterState);
        }
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(6));
        assertThat(clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).size(), equalTo(3));

        for (ShardRouting shard : clusterState.getRoutingNodes().shardsWithState(UNASSIGNED)) {
            assertEquals(shard.unassignedInfo().getReason(), UnassignedInfo.Reason.NODE_LEFT);
            assertFalse(shard.primary());
        }
    }

    public void testSingleZoneReplicaUnassignedOnSkewnessWithTwoShardCopies() {
        AllocationService strategy = createAllocationService(Settings.builder()
            .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
            .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_INITIAL_REPLICAS_RECOVERIES_SETTING.getKey(), 10)
            .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(), "always")
            .put(AwarenessAllocationDecider.CLUSTER_ROUTING_ALLOCATION_AWARENESS_FORCED_ALLOCATION_DISABLE_SETTING.getKey(), true)
            .put("cluster.routing.allocation.awareness.attribute.zone.capacity", 3)
            .put("cluster.routing.allocation.awareness.force.zone.values", "zone1")
            .put("cluster.routing.allocation.awareness.attributes", "zone")
            .build());

        logger.info("Building initial routing table for 'testSingleZoneReplicaUnassignedOnSkewnessWithTwoShardCopies'");

        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(3).numberOfReplicas(1))
            .build();

        RoutingTable initialRoutingTable = RoutingTable.builder()
            .addAsNew(metadata.index("test"))
            .build();

        ClusterState clusterState = ClusterState.builder(org.opensearch.cluster.ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        logger.info("--> adding two nodes on same rack and do rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
            .add(newNode("node1", singletonMap("zone", "zone1")))
            .add(newNode("node2", singletonMap("zone", "zone1")))
            .add(newNode("node3", singletonMap("zone", "zone1")))
        ).build();
        clusterState = strategy.reroute(clusterState, "reroute");
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(3));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> replicas are initializing");
        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(3));
        assertThat(clusterState.getRoutingNodes().shardsWithState(INITIALIZING).size(), equalTo(3));

        logger.info("--> start the shards (replicas)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> all shards are started");
        assertThat(clusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(6));

        logger.info("--> do another reroute, make sure nothing moves");
        assertThat(strategy.reroute(clusterState, "reroute").routingTable(), sameInstance(clusterState.routingTable()));

        //remove one node to make zone1 skewed
        clusterState = removeNode(clusterState, randomFrom("node1", "node2", "node3"), strategy);
        clusterState = strategy.reroute(clusterState, "reroute");

        while (clusterState.getRoutingNodes().shardsWithState(INITIALIZING).isEmpty() == false) {
            clusterState = startInitializingShardsAndReroute(strategy, clusterState);
        }
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().shardsWithState(STARTED).size(), equalTo(4));
        assertThat(clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).size(), equalTo(2));

        for (ShardRouting shard : clusterState.getRoutingNodes().shardsWithState(UNASSIGNED)) {
            assertEquals(shard.unassignedInfo().getReason(), UnassignedInfo.Reason.NODE_LEFT);
            assertFalse(shard.primary());
        }
    }
}
