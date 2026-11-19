package com.voiceagent.oneplus13.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
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
        assertTrue("CI must provide the tiny GGUF fixture", model.isFile)
        val answer = withTimeout(120_000) { LocalInference.complete(model.absolutePath, "Tell a story.", emptyList(), "Once upon a time") }
        assertTrue(answer.isNotBlank())
    }
}
