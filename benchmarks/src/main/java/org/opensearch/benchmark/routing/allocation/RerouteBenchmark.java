/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.benchmark.routing.allocation;

import org.openjdk.jmh.annotations.*;

import org.opensearch.Version;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.routing.allocation.AllocationService;
import org.opensearch.common.logging.LogConfigurator;
import org.opensearch.common.settings.Settings;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Fork(1)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@SuppressWarnings("unused") // invoked by benchmarking framework
public class RerouteBenchmark {
    @Param({
        // indices| nodes
        "    5000|  500|",
    })
    public String indicesNodes = "1|1";
    public int numIndices;
    public int numNodes;
    public int numShards = 9;
    public int numReplicas = 5;


    private AllocationService allocationService;
    private ClusterState initialClusterState;

    @Setup
    public void setUp() throws Exception {
        LogConfigurator.setNodeName("test");
        final String[] params = indicesNodes.split("\\|");
        numIndices = toInt(params[0]);
        numNodes = toInt(params[1]);

        int totalShardCount = (numReplicas + 1) * numShards * numIndices;
        allocationService = Allocators.createAllocationService(
            Settings.builder()
                .put("cluster.routing.allocation.awareness.attributes", "zone")
                .put("cluster.routing.allocation.load_awareness.provisioned_capacity", numNodes)
                .put("cluster.routing.allocation.load_awareness.skew_factor", "50")
                .put("cluster.routing.allocation.node_concurrent_recoveries", "2")
                .build()
        );
        Metadata.Builder mb = Metadata.builder();
        for (int i = 1; i <= numIndices; i++) {
            mb.put(
                IndexMetadata.builder("test_" + i)
                    .settings(Settings.builder().put("index.version.created", Version.CURRENT))
                    .numberOfShards(numShards)
                    .numberOfReplicas(numReplicas)
            );
        }

        Metadata metadata = mb.build();
        RoutingTable.Builder rb = RoutingTable.builder();
        for (int i = 1; i <= numIndices; i++) {
            rb.addAsNew(metadata.index("test_" + i));
        }
        RoutingTable routingTable = rb.build();
        initialClusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(routingTable)
            .nodes(setUpClusterNodes(numNodes))
            .build();

//        initialClusterState = allocationService.reroute(initialClusterState, "reroute");
//        while (initialClusterState.getRoutingNodes().hasUnassignedShards()) {
//            initialClusterState = allocationService.applyStartedShards(
//                initialClusterState,
//                initialClusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING)
//            );
//            initialClusterState = allocationService.reroute(initialClusterState, "reroute");
//        }
//        // Ensure all shards are started
//        while (initialClusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).size() > 0) {
//            initialClusterState = allocationService.applyStartedShards(
//                initialClusterState,
//                initialClusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING)
//            );
//        }
//        assert (initialClusterState.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size() == totalShardCount);
//        assert (initialClusterState.getRoutingNodes().shardsWithState(ShardRoutingState.INITIALIZING).size() == 0);
//        assert (initialClusterState.getRoutingNodes().shardsWithState(ShardRoutingState.RELOCATING).size() == 0);
    }

    @Benchmark
    public ClusterState measureShardAllocationEmptyCluster() throws Exception {
        return allocationService.reroute(initialClusterState, "reroute");
    }

    private int toInt(String v) {
        return Integer.valueOf(v.trim());
    }

    private DiscoveryNodes.Builder setUpClusterNodes(int nodes) {
        DiscoveryNodes.Builder nb = DiscoveryNodes.builder();
        for (int i = 1; i <= nodes; i++) {
            Map<String, String> attributes = new HashMap<>();
            attributes.put("zone", "zone_" + (i % 3));
            nb.add(Allocators.newNode("node_0_" + i, attributes));
        }
        return nb;
    }
}
