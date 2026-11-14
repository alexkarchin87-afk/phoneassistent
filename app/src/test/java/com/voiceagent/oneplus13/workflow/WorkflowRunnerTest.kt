package com.voiceagent.oneplus13.workflow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRunnerTest {
    private fun block(id: String) = WorkflowBlock(id = id, type = BlockType.DELAY)

    @Test fun cycleReportsFailureInsteadOfSuccess() = runBlocking {
        val a = block("a")
        val b = block("b")
        val workflow = Workflow(name = "cycle", blocks = mutableListOf(a, b),
            connections = mutableListOf(WorkflowConnection("a", "b"), WorkflowConnection("b", "a")))
        val steps = mutableListOf<WorkflowRunner.StepResult>()
        WorkflowRunner { true }.run(workflow) { steps.add(it) }
        assertTrue(steps.last() is WorkflowRunner.StepResult.Failed)
        assertTrue(steps.none { it is WorkflowRunner.StepResult.Finished })
    }

    @Test fun missingConnectionStopsAsFailure() = runBlocking {
        val workflow = Workflow(name = "broken", blocks = mutableListOf(block("a")),
            connections = mutableListOf(WorkflowConnection("a", "missing")))
        val steps = mutableListOf<WorkflowRunner.StepResult>()
        WorkflowRunner { true }.run(workflow) { steps.add(it) }
        assertTrue(steps.last() is WorkflowRunner.StepResult.Failed)
    }

    @Test fun stopsOnFailedBlock() = runBlocking {
        val workflow = Workflow(name = "stop", blocks = mutableListOf(block("a"), block("b")),
            connections = mutableListOf(WorkflowConnection("a", "b")))
        val executed = mutableListOf<String>()
        val steps = mutableListOf<WorkflowRunner.StepResult>()
        WorkflowRunner { executed.add(it.id); false }.run(workflow) { steps.add(it) }
        assertEquals(listOf("a"), executed)
        assertTrue(steps.last() is WorkflowRunner.StepResult.Failed)
    }
}
