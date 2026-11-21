package com.voiceagent.oneplus13.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voiceagent.oneplus13.core.security.SecretsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupSecurityTest {
    @Test fun importingLegacyBackupDoesNotReplaceCurrentCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SecretsStore(context)
        val key = SecretsStore.KEY_TELEGRAM_OWNER_ID
        val old = store.get(key)
        val file = File(context.cacheDir, "backup-security-test.json")
        try {
            store.put(key, "original-owner")
            file.writeText("""{"format_version":1,"secrets":{"telegram_owner_id":"attacker"},"project_secrets":{"telegram_owner_id":"other"}}""")
            assertTrue(BackupManager(context).importBackup(file))
            assertEquals("original-owner", store.get(key))
        } finally {
            if (old == null) store.remove(key) else store.put(key, old)
            file.delete()
        }
    }
}
