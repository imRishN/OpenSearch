/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.admin.cluster.decommission.awareness.delete;

import org.opensearch.action.support.clustermanager.ClusterManagerNodeOperationRequestBuilder;
import org.opensearch.client.OpenSearchClient;

public class DeleteDecommissionRequestBuilder extends ClusterManagerNodeOperationRequestBuilder<
    DeleteDecommissionRequest,
    DeleteDecommissionResponse,
    DeleteDecommissionRequestBuilder> {

    public DeleteDecommissionRequestBuilder(OpenSearchClient client, DeleteDecommissionAction action) {
        super(client, action, new DeleteDecommissionRequest());
    }
}
