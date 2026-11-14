package com.voiceagent.oneplus13.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptRoutingTest {
    @Test fun lectureSpeechNeverBecomesACommandDuringConversationWindow() {
        assertEquals(TranscriptRoute.LECTURE, routeTranscript(true, false, false, true))
    }

    @Test fun wakeWordAndConfirmationRemainUsableWhileRecording() {
        assertEquals(TranscriptRoute.COMMAND, routeTranscript(true, true, false, false))
        assertEquals(TranscriptRoute.COMMAND, routeTranscript(true, false, true, false))
    }

    @Test fun ordinaryBackgroundSpeechIsIgnoredWhenNotRecording() {
        assertEquals(TranscriptRoute.IGNORE, routeTranscript(false, false, false, false))
    }
}
