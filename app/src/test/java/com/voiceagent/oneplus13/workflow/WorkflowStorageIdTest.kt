package com.voiceagent.oneplus13.workflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowStorageIdTest {
    @Test fun rejectsTraversalAndAcceptsExistingIdentifierFormat() {
        assertTrue(validWorkflowId("a1b2c3d4"))
        assertTrue(validWorkflowId("task-123_ABC"))
        listOf("", "../secrets", "/tmp/x", "..", "a/b", "a\\b", "x".repeat(81)).forEach {
            assertFalse(it, validWorkflowId(it))
        }
    }
}
