package com.voiceagent.oneplus13.core

internal enum class TranscriptRoute { LECTURE, COMMAND, IGNORE }

/** Lecture speech is not treated as commands unless the user wakes the assistant
 * or answers a pending confirmation. */
internal fun routeTranscript(
    recording: Boolean,
    hasWakeWord: Boolean,
    awaitingConfirmation: Boolean,
    conversationActive: Boolean
): TranscriptRoute = when {
    recording && !hasWakeWord && !awaitingConfirmation -> TranscriptRoute.LECTURE
    hasWakeWord || awaitingConfirmation || conversationActive -> TranscriptRoute.COMMAND
    else -> TranscriptRoute.IGNORE
}
