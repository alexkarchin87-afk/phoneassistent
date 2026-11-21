package com.voiceagent.oneplus13.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Reads screen content and performs taps/swipes/text-entry on behalf of the agent.
 * This is the component that lets AgentOrchestrator "operate the phone" (open an app,
 * tap a button, fill a field) instead of only talking.
 *
 * Must be enabled manually once by the user under
 * Settings > Accessibility > VoiceAgent, since Android does not allow silently
 * granting this permission.
 */
class AccessibilityAgent : AccessibilityService() {

    companion object {
        // Set by onServiceConnected(); SystemController posts commands into this instance.
        @Volatile var instance: AccessibilityAgent? = null
        private const val MAX_ANCESTOR_SEARCH_DEPTH = 8
        private const val MAX_SCAN_DEPTH = 64
        private const val MAX_SCAN_NODES = 1500
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally minimal: SystemController pulls the tree on-demand via
        // dumpVisibleText() rather than reacting to every event, to keep overhead low.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Returns a flattened list of visible text/labels on screen, used to ground
     *  LLM decisions. Includes contentDescription (accessibility label), not just
     *  visible text — many icon-only buttons (like a "like"/heart control in a
     *  music player) have no text at all, only a content description. */
    fun dumpVisibleText(): List<String> {
        val out = mutableListOf<String>()
        scan { node ->
            if (node.isPassword || node.isEditable) Scan.SKIP_CHILDREN else {
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
                Scan.CONTINUE
            }
        }
        return out
    }

    /** Taps the first node whose visible text OR content description contains
     *  [label] (case-insensitive) — see [dumpVisibleText] for why both matter. */
    fun tapByText(label: String): Boolean {
        val cleanLabel = label.trim()
        if (cleanLabel.isEmpty()) return false
        return tapMatching(cleanLabel, exact = false)
    }

    /** Exact labels avoid selecting an inverse action that merely contains a word. */
    fun tapByExactText(label: String): Boolean {
        val cleanLabel = label.trim()
        if (cleanLabel.isEmpty()) return false
        return tapMatching(cleanLabel, exact = true)
    }

    private enum class Scan { CONTINUE, SKIP_CHILDREN, STOP }

    @Suppress("DEPRECATION")
    private fun scan(visit: (AccessibilityNodeInfo) -> Scan): Boolean {
        val root = rootInActiveWindow ?: return false
        val pending = java.util.ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        pending.push(root to 0)
        var visited = 0
        try {
            while (pending.isNotEmpty() && visited < MAX_SCAN_NODES) {
                val (node, depth) = pending.pop()
                try {
                    visited++
                    when (visit(node)) {
                        Scan.STOP -> return true
                        Scan.SKIP_CHILDREN -> continue
                        Scan.CONTINUE -> Unit
                    }
                    if (depth < MAX_SCAN_DEPTH) {
                        for (i in node.childCount - 1 downTo 0) {
                            if (visited + pending.size >= MAX_SCAN_NODES) break
                            node.getChild(i)?.let { pending.push(it to depth + 1) }
                        }
                    }
                } finally { node.recycle() }
            }
        } finally {
            while (pending.isNotEmpty()) pending.pop().first.recycle()
        }
        return false
    }

    private fun tapMatching(label: String, exact: Boolean): Boolean {
        var result = false
        scan { node ->
            val matches = listOf(node.text, node.contentDescription).any { value ->
                val text = value?.toString()?.trim() ?: return@any false
                if (exact) text.equals(label, ignoreCase = true) else text.contains(label, ignoreCase = true)
            }
            if (matches) {
                result = clickNodeOrAncestor(node)
                Scan.STOP
            } else Scan.CONTINUE
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        var current = node.parent
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_SEARCH_DEPTH) {
            val parent = current
            current = try {
                if (parent.isClickable) return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.parent
            } finally { parent.recycle() }
            depth++
        }
        current?.recycle()
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Returns true only when Android reports that the gesture completed. */
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return withTimeoutOrNull(durationMs + 3_000L) {
            suspendCancellableCoroutine { continuation ->
                val finished = AtomicBoolean(false)
                fun finish(value: Boolean) {
                    if (finished.compareAndSet(false, true) && continuation.isActive) continuation.resume(value)
                }
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { finish(true) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { finish(false) }
                }, null)
                if (!accepted) finish(false)
                continuation.invokeOnCancellation { finished.set(true) }
            }
        } ?: false
    }
}
