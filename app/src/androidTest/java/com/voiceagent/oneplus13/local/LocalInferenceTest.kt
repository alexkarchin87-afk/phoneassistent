package com.voiceagent.oneplus13.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalInferenceTest {
    @Test fun nativeCpuInferenceProducesText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = File(context.filesDir, "tiny.gguf")
        assertTrue("CI must provide the complete tiny GGUF fixture", model.isFile && model.length() > 1_000_000)
        ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val answer = withTimeout(120_000) { LocalInference.complete("/proc/self/fd/${fd.fd}", "Tell a story.", emptyList(), "Once upon a time", maxTokens = 8) }
            assertTrue(answer.isNotBlank())
        }
    }
}
