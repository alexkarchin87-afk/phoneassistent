package com.voiceagent.oneplus13.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
        var instance: AccessibilityAgent? = null
        private const val MAX_ANCESTOR_SEARCH_DEPTH = 8
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
        val root = rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<String>()
        collectText(root, out)
        return out
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        // Editable fields can contain passwords, recovery codes, and private input.
        if (node.isPassword || node.isEditable) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectText(it, out) }
        }
    }

    /** Taps the first node whose visible text OR content description contains
     *  [label] (case-insensitive) — see [dumpVisibleText] for why both matter. */
    fun tapByText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNodeByText(root, label) ?: return false
        // Many icon-only controls put the contentDescription on a plain, non-clickable
        // ImageView/TextView, while the actual click target is a parent container
        // (Row/Button). Clicking the leaf we matched on would silently no-op.
        val clickable = nearestClickableAncestor(target) ?: target
        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun nearestClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_SEARCH_DEPTH) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val text = node.text?.toString()
        val description = node.contentDescription?.toString()
        if (text?.contains(label, ignoreCase = true) == true) return node
        if (description?.contains(label, ignoreCase = true) == true) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findNodeByText(child, label)?.let { return it }
            }
        }
        return null
    }

    /** Performs a straight-line swipe gesture, e.g. for scrolling a feed. */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }
}

