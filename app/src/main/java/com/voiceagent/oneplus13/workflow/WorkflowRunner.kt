package com.voiceagent.oneplus13.workflow

import com.voiceagent.oneplus13.core.TTSEngine
import com.voiceagent.oneplus13.system.SystemController
import kotlinx.coroutines.delay

/**
 * Исполняет сценарий блок за блоком, следуя связям от [Workflow.startBlock].
 * Никакой отдельной "рантайм-логики" здесь нет намеренно — каждый case это
 * тот же самый вызов, что уже используется в
 * [com.voiceagent.oneplus13.agent.AgentOrchestrator.dispatch] для голосовых
 * команд, просто выполненный по записанной цепочке, а не по одному действию
 * за раз.
 *
 * Обрывает выполнение при первой ошибке (не пытается продолжить сценарий
 * вслепую, если предыдущий шаг не удался) и сообщает об этом через [onStep].
 */
class WorkflowRunner internal constructor(
    private val executeBlock: suspend (WorkflowBlock) -> Boolean
) {
    constructor(systemController: SystemController, tts: TTSEngine) :
        this({ block -> runBlock(block, systemController, tts) })
    sealed class StepResult {
        data class Started(val block: WorkflowBlock) : StepResult()
        data class Finished(val stepsRun: Int) : StepResult()
        data class Failed(val block: WorkflowBlock, val reason: String) : StepResult()
    }

    suspend fun run(workflow: Workflow, onStep: suspend (StepResult) -> Unit = {}) {
        var current = workflow.startBlock()
        var stepsRun = 0
        val visited = mutableSetOf<String>()

        while (current != null) {
            if (!visited.add(current.id)) {
                onStep(StepResult.Failed(current, "Обнаружен цикл в сценарии"))
                return
            }
            onStep(StepResult.Started(current))

            val ok = try {
                executeBlock(current)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onStep(StepResult.Failed(current, error.message ?: "Ошибка шага"))
                return
            }
            if (!ok) {
                onStep(StepResult.Failed(current, "Шаг \"${current.type.displayName}\" не выполнился"))
                return
            }
            stepsRun++
            val currentId = current.id
            val next = workflow.next(currentId)
            if (next == null && workflow.connections.any { it.fromId == currentId }) {
                onStep(StepResult.Failed(current, "Следующий блок не найден"))
                return
            }
            current = next
        }
        onStep(StepResult.Finished(stepsRun))
    }

    companion object {
    private suspend fun runBlock(block: WorkflowBlock, systemController: SystemController, tts: TTSEngine): Boolean = when (block.type) {
        BlockType.OPEN_APP -> block.params["package"]?.let { systemController.openApp(it) } ?: false
        BlockType.OPEN_URL -> block.params["url"]?.let { systemController.openUrl(it); true } ?: false
        BlockType.SET_VOLUME -> block.params["percent"]?.toIntOrNull()?.let {
            systemController.setMediaVolume(it); true
        } ?: false
        BlockType.TAP_TEXT -> block.params["label"]?.let { systemController.tapByText(it) } ?: false
        BlockType.SWIPE_UP -> { systemController.swipeUp(); true }
        BlockType.SPEAK -> block.params["text"]?.let { tts.speak(it); true } ?: false
        BlockType.DELAY -> {
            val seconds = block.params["seconds"]?.toLongOrNull() ?: 1L
            delay(seconds.coerceIn(0, 60) * 1000)
            true
        }
    }
    }
}

