package com.voiceagent.oneplus13.lecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LectureCaptureTest {
    @Test fun repeatedStartDoesNotDiscardExistingRecording() {
        val capture = LectureCapture()
        assertTrue(capture.start("first"))
        capture.append("Первая часть")
        assertFalse(capture.start("second"))
        capture.append("Вторая часть")
        val result = capture.finish()!!
        assertEquals("first", result.title)
        assertEquals("Первая часть Вторая часть", result.fullTranscript())
        assertFalse(capture.isActive())
        assertNull(capture.finish())
    }

    @Test fun appendingWhileStoppedDoesNotLeakIntoNextSession() {
        val capture = LectureCapture()
        capture.append("лишнее")
        capture.start("test")
        capture.append("текст")
        assertEquals("текст", capture.finish()!!.fullTranscript())
    }

    @Test fun concurrentChunksAreNotLost() {
        val capture = LectureCapture()
        capture.start("test")
        val workers = (1..4).map { number ->
            Thread { repeat(100) { capture.append("текст$number") } }.apply { start() }
        }
        workers.forEach { it.join() }
        assertEquals(400, capture.finish()!!.chunks.size)
    }
}
