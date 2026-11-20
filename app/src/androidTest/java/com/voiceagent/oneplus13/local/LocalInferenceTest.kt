package com.voiceagent.oneplus13.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalInferenceTest {
    @Test fun nativeCpuInferenceProducesText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = File(context.filesDir, "tiny.gguf")
        assertTrue("CI must provide the complete tiny GGUF fixture", model.isFile && model.length() > 1_000_000)
        LocalPerformance.save(context, LocalPerformance.COOL)
        val coolAnswer = ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val answer = withTimeout(120_000) { LocalInference.completeDescriptorForTest(context, fd, "Tell a story.", "Once upon a time") }
            assertTrue(answer.isNotBlank())
            answer
        }
        LocalPerformance.save(context, LocalPerformance.FAST)
        ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val fastAnswer = withTimeout(120_000) { LocalInference.completeDescriptorForTest(context, fd, "Tell a story.", "Once upon a time") }
            assertEquals("Changing CPU profile should preserve this deterministic reply", coolAnswer, fastAnswer)
        }
        val cancelled = async {
            ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                LocalInference.completeDescriptorForTest(context, fd, "Tell a story.", "Once upon a time", maxTokens = 1536)
            }
        }
        delay(200)
        cancelled.cancelAndJoin()
        ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val after = withTimeout(120_000) { LocalInference.completeDescriptorForTest(context, fd, "Tell a story.", "Once upon a time") }
            assertEquals("Inference should still work after cancellation", coolAnswer, after)
        }
    }
    @Test fun qwen35FastAndThoughtfulModes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = File(context.filesDir, "qwen35.gguf")
        assertTrue("CI must supply the Qwen 3.5 test model", model.isFile && model.length() > 500_000_000L)
        ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val fast = withTimeout(180_000) { LocalInference.completeDescriptorForTest(context, fd, "Be brief.", "Say hello.", maxTokens = 12) }
            assertTrue(fast.isNotBlank())
        }
        val loadsAfterFast = LocalInference.modelLoadCountForTest()
        ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val thoughtful = runCatching { withTimeout(180_000) { LocalInference.completeDescriptorForTest(context, fd, "Be brief.", "Say hello.", thoughtful = true, maxTokens = 12) } }
            // A short thinking budget may end before the answer. It must never leak partial reasoning.
            thoughtful.exceptionOrNull()?.let { error -> assertTrue(error.message.orEmpty().contains("LOCAL_THINKING_INCOMPLETE")) }
            thoughtful.getOrNull()?.let { assertTrue(it.isNotBlank() && !it.contains("<think>")) }
        }
        assertTrue("The same model should be reused for the next request", LocalInference.modelLoadCountForTest() == loadsAfterFast)
        LocalInference.releaseForMemoryPressure()
        assertTrue("The model must be released under memory pressure", !LocalInference.hasCachedModelForTest())
        Unit
    }
}
