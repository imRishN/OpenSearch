/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.common.util;

/**
 * Utility class to manage kill switches. Kill switches are system properties that must be set on the JVM.
 * These are used to kill features in case of unforeseen scenarios.
 *
 * @opensearch.internal
 */
public class KillSwitch {

    /**
     * This switch kills the action taken by decommission api and lets the decommissioned nodes join back the cluster.
     * And any core functionality change will be reverted
     */
    public static final String AWARENESS_ATTRIBUTE_DECOMMISSION = "opensearch.feature.switch.awareness_attribute_decommission.disabled";

    /**
     * Used to test kill switches whose values are expected to be booleans.
     * This method returns true if the value is "true" (case-insensitive),
     * and false otherwise.
     */
    public static boolean isDisabled(String killSwitchName) {
        return "true".equalsIgnoreCase(System.getProperty(killSwitchName));
    }
}
