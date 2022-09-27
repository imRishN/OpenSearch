/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.common.util;

import org.junit.BeforeClass;
import org.opensearch.common.SuppressForbidden;
import org.opensearch.test.OpenSearchTestCase;

import java.security.AccessController;
import java.security.PrivilegedAction;

public class KillSwitchTests extends OpenSearchTestCase {

    @SuppressForbidden(reason = "sets the kill switch")
    @BeforeClass
    public static void killSwitch() {
        AccessController.doPrivileged((PrivilegedAction<String>) () -> System.setProperty(KillSwitch.AWARENESS_ATTRIBUTE_DECOMMISSION, "true"));
    }

    public void testMissingSwitch() {
        String testSwitch = "missingSwitch";
        assertNull(System.getProperty(testSwitch));
        assertFalse(KillSwitch.isDisabled(testSwitch));
    }

    public void testNonBooleanKillSwitch() {
        String javaVersionProperty = "java.version";
        assertNotNull(System.getProperty(javaVersionProperty));
        assertFalse(KillSwitch.isDisabled(javaVersionProperty));
    }

    public void testDecommissionKillSwitch() {
        String awarenessAttributeDecommissionFlag = KillSwitch.AWARENESS_ATTRIBUTE_DECOMMISSION;
        assertNotNull(System.getProperty(awarenessAttributeDecommissionFlag));
        assertTrue(KillSwitch.isDisabled(awarenessAttributeDecommissionFlag));
    }
}
