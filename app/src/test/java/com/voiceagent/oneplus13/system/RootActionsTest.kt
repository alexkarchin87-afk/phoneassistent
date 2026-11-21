package com.voiceagent.oneplus13.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootActionsTest {
    @Test fun recognizesOnlyRootUid() {
        assertTrue(RootActions.isRootIdentity("uid=0(root) gid=0(root)"))
        assertFalse(RootActions.isRootIdentity("uid=2000(shell) gid=2000(shell)"))
        assertFalse(RootActions.isRootIdentity("uid=1000(system) gid=0(root)"))
        assertFalse(RootActions.isRootIdentity("permission denied"))
    }
}
