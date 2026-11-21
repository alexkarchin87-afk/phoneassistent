package com.voiceagent.oneplus13.workflow

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkflowIndexTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun missesDoNotReadFilesAgainAndEditsInvalidateAcrossClients() {
        var reads = 0
        val dir = temp.newFolder()
        val files = WorkflowFiles(dir) { reads++; it.readText() }
        repeat(20) { i -> files.save(Workflow("id$i", "Сценарий $i", "код $i!")) }
        // Cold lookup indexes existing files, including ones saved by the previous app version.
        repeat(100) { assertNull(files.findByTriggerIn("обычная реплика")) }
        assertEquals(20, reads)
        assertEquals("id1", files.findByTriggerIn("КОД 1!")?.id)
        assertEquals(21, reads)
        files.save(Workflow("id1", "Новый", "привет"))
        assertNull(files.findByTriggerIn("КОД 1!"))
        assertEquals("Новый", files.findByTriggerIn("привет")?.name)
        assertEquals("id1", files.findByName("новый")?.id)
        files.delete("id1")
        assertNull(files.findByName("Новый"))
    }
}
