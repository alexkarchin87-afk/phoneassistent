package com.voiceagent.oneplus13.workflow

/** Builds a complete, bounded description of the exact linear workflow to approve. */
internal fun describeWorkflowForConfirmation(workflow: Workflow): String? {
    if (workflow.blocks.isEmpty() || workflow.blocks.size > 5) return null
    if (workflow.blocks.map { it.id }.toSet().size != workflow.blocks.size) return null
    val visited = mutableSetOf<String>()
    val descriptions = mutableListOf<String>()
    var current = workflow.startBlock()
    while (current != null) {
        if (!visited.add(current.id)) return null
        val outgoing = workflow.connections.filter { it.fromId == current.id }
        if (outgoing.size > 1) return null
        val params = current.params.toSortedMap().entries.joinToString(", ") { (key, value) ->
            "$key: $value"
        }
        descriptions.add(current.type.displayName + if (params.isEmpty()) "" else " ($params)")
        val next = workflow.next(current.id)
        if (outgoing.isNotEmpty() && next == null) return null
        current = next
    }
    if (visited.size != workflow.blocks.size) return null
    val description = descriptions.joinToString("; ")
    return description.takeIf { it.length <= 600 }
}
