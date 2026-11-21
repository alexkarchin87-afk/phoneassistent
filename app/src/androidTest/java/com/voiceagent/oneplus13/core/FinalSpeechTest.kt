package com.voiceagent.oneplus13.core

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

@RunWith(AndroidJUnit4::class)
class FinalSpeechTest {
    @Test fun draftsCanBeSkippedWithoutChangingCompletedRussianPhrase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = instrumentation.context.assets.open("russian-digits.pcm").use { it.readBytes() }
        val path = StorageService.sync(instrumentation.targetContext, "model-ru-small", "model")
        Model(path).use { model ->
            val chunks = (fixture + ByteArray(64000)).asList().chunked(6400).map { it.toByteArray() }
            fun decode(drafts: Boolean): Pair<List<String>, Long> {
                val phrases = mutableListOf<String>()
                fun record(json: String) {
                    JSONObject(json).getString("text").takeIf { it.isNotBlank() }?.let(phrases::add)
                }
                val recognizer = if (drafts) Recognizer(model, 16000f) else FinalSpeechRecognizer(model)
                val start = Process.getElapsedCpuTime()
                recognizer.use {
                    for (chunk in chunks) {
                        if (it.acceptWaveForm(chunk, chunk.size)) record(it.result)
                        else it.partialResult
                    }
                    record(it.finalResult)
                }
                return phrases to (Process.getElapsedCpuTime() - start)
            }
            val expected = decode(true).first
            assertTrue("Speech fixture should yield words", expected.isNotEmpty())
            assertEquals(expected, decode(false).first)
            val draftTimes = mutableListOf<Long>()
            val finalTimes = mutableListOf<Long>()
            repeat(4) { trial ->
                for (drafts in listOf(trial % 2 == 0, trial % 2 != 0)) {
                    val (phrase, cpuMs) = decode(drafts)
                    assertEquals(expected, phrase)
                    (if (drafts) draftTimes else finalTimes).add(cpuMs)
                }
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("speech_drafts_cpu_ms", draftTimes.joinToString(","))
                putString("speech_final_cpu_ms", finalTimes.joinToString(","))
                putString("speech_recognized", expected.joinToString(" / "))
            })
        }
    }
}
