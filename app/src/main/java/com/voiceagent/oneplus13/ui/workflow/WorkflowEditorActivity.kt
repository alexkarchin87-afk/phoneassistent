package com.voiceagent.oneplus13.ui.workflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.voiceagent.oneplus13.core.LLMClient
import com.voiceagent.oneplus13.core.TTSEngine
import com.voiceagent.oneplus13.system.SystemController
import com.voiceagent.oneplus13.ui.theme.VoiceAgentTheme
import com.voiceagent.oneplus13.workflow.BlockType
import com.voiceagent.oneplus13.workflow.Workflow
import com.voiceagent.oneplus13.workflow.WorkflowAiBuilder
import com.voiceagent.oneplus13.workflow.WorkflowBlock
import com.voiceagent.oneplus13.workflow.WorkflowConnection
import com.voiceagent.oneplus13.workflow.WorkflowRunner
import com.voiceagent.oneplus13.workflow.WorkflowStorage
import kotlinx.coroutines.launch

/**
 * "Мастерская сценариев" — визуальный no-code редактор поверх тех же
 * действий, что Ваня уже умеет выполнять по голосу (см. [BlockType]).
 *
 * Два экрана в одной Activity: список сохранённых сценариев ->
 * канвас для конкретного сценария. Кто не хочет возиться со схемой — есть
 * текстовое поле "опиши словами", которое собирает граф блоков через
 * [WorkflowAiBuilder] (DeepSeek-R1 + проверка Claude).
 */
class WorkflowEditorActivity : ComponentActivity() {
    private var speech: TTSEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storage = WorkflowStorage(this)
        val aiBuilder = WorkflowAiBuilder(LLMClient(this))
        val runner = WorkflowRunner(SystemController(this), TTSEngine(this).also { speech = it })

        setContent {
            VoiceAgentTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WorkflowWorkshopScreen(
                        storage = storage,
                        aiBuilder = aiBuilder,
                        runner = runner,
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        speech?.shutdown()
        speech = null
        super.onDestroy()
    }
}

@Composable
private fun WorkflowWorkshopScreen(
    storage: WorkflowStorage,
    aiBuilder: WorkflowAiBuilder,
    runner: WorkflowRunner,
    onClose: () -> Unit
) {
    var openWorkflow by remember { mutableStateOf<Workflow?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    val current = openWorkflow
    if (current == null) {
        WorkflowListScreen(
            storage = storage,
            aiBuilder = aiBuilder,
            refreshTick = refreshTick,
            onOpen = { openWorkflow = it },
            onClose = onClose,
            onCreated = { openWorkflow = it; refreshTick++ }
        )
    } else {
        WorkflowCanvasScreen(
            workflow = current,
            storage = storage,
            runner = runner,
            onBack = { openWorkflow = null; refreshTick++ }
        )
    }
}

@Composable
private fun WorkflowListScreen(
    storage: WorkflowStorage,
    aiBuilder: WorkflowAiBuilder,
    refreshTick: Int,
    onOpen: (Workflow) -> Unit,
    onClose: () -> Unit,
    onCreated: (Workflow) -> Unit
) {
    val scope = rememberCoroutineScope()
    var workflows by remember(refreshTick) { mutableStateOf(storage.listAll()) }
    var aiDescription by remember { mutableStateOf("") }
    var isBuilding by remember { mutableStateOf(false) }
    var buildError by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Мастерская сценариев", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Закрыть") }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Собери цепочку из блоков-действий (открыть приложение, громкость, " +
                "тап по тексту...) — Ваня выполнит их по порядку по команде или по " +
                "своей кодовой фразе.",
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Не хочешь разбираться со схемой?", fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Опиши словами, что должен делать сценарий — соберём с помощью ИИ.", color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = aiDescription,
                    onValueChange = { aiDescription = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Например: включи громкость на 50 и открой Spotify") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    enabled = aiDescription.isNotBlank() && !isBuilding,
                    onClick = {
                        val description = aiDescription
                        isBuilding = true
                        buildError = null
                        scope.launch {
                            try {
                                val wf = aiBuilder.build(description)
                                storage.save(wf)
                                aiDescription = ""
                                onCreated(wf)
                            } catch (t: Throwable) {
                                buildError = "Не получилось собрать: ${t.message ?: "ошибка ИИ"}. Можно собрать руками — жми \"+ Новый сценарий\"."
                            } finally {
                                isBuilding = false
                            }
                        }
                    }
                ) {
                    Text(if (isBuilding) "Собираю..." else "Собрать с помощью ИИ")
                }
                buildError?.let { Spacer(modifier = Modifier.height(6.dp)); Text(it, color = Color(0xFFB00020)) }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Сохранённые сценарии", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onCreated(Workflow(name = "Новый сценарий")) }) { Text("+ Новый сценарий") }
        }

        if (workflows.isEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Пока пусто — собери первый сценарий выше.", color = Color.Gray)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(workflows) { wf ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onOpen(wf) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(wf.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${wf.blocks.size} шаг(ов)" +
                                    (wf.triggerPhrase?.takeIf { it.isNotBlank() }?.let { " · кодовая фраза: \"$it\"" } ?: ""),
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowCanvasScreen(
    workflow: Workflow,
    storage: WorkflowStorage,
    runner: WorkflowRunner,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(workflow.name) }
    var triggerPhrase by remember { mutableStateOf(workflow.triggerPhrase.orEmpty()) }
    val blocks = remember { mutableStateListOf<WorkflowBlock>().apply { addAll(workflow.blocks) } }
    val connections = remember { mutableStateListOf<WorkflowConnection>().apply { addAll(workflow.connections) } }

    var connectMode by remember { mutableStateOf(false) }
    var connectFrom by remember { mutableStateOf<String?>(null) }
    var editingBlock by remember { mutableStateOf<WorkflowBlock?>(null) }
    var showAddPicker by remember { mutableStateOf(false) }
    var runStatus by remember { mutableStateOf<String?>(null) }

    fun currentWorkflow() = Workflow(workflow.id, name, triggerPhrase.ifBlank { null }, blocks.toMutableList(), connections.toMutableList())

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = {
                connectMode = !connectMode
                connectFrom = null
            }) { Text(if (connectMode) "Готово" else "Соединить блоки") }
            Button(onClick = {
                storage.save(currentWorkflow())
                runStatus = "Сохранено"
            }) { Text("Сохранить") }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Название сценария") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = triggerPhrase,
            onValueChange = { triggerPhrase = it },
            label = { Text("Кодовая фраза (необязательно) — например \"доброе утро\"") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        )

        if (connectMode) {
            Text(
                "Режим соединения: коснись блока-источника, потом блока, который должен идти следующим.",
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        runStatus?.let { Text(it, color = Color.Gray, modifier = Modifier.padding(horizontal = 12.dp)) }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Линии связей — под блоками. BLOCK_WIDTH_DP/BLOCK_HEIGHT_DP переведены
            // в пиксели через DrawScope.toPx() (учитывает плотность экрана) — если
            // взять их как сырые числа, линии разъедутся с реальными краями блоков
            // на любом экране с плотностью, отличной от 1x (то есть почти на любом).
            Canvas(modifier = Modifier.fillMaxSize()) {
                val blockWidthPx = BLOCK_WIDTH_DP.toPx()
                val blockHeightPx = BLOCK_HEIGHT_DP.toPx()
                connections.forEach { conn ->
                    val from = blocks.firstOrNull { it.id == conn.fromId } ?: return@forEach
                    val to = blocks.firstOrNull { it.id == conn.toId } ?: return@forEach
                    drawLine(
                        color = Color.Black.copy(alpha = 0.4f),
                        start = Offset(from.x + blockWidthPx / 2, from.y + blockHeightPx),
                        end = Offset(to.x + blockWidthPx / 2, to.y),
                        strokeWidth = 4f
                    )
                }
            }

            blocks.forEach { block ->
                key(block.id) {
                    WorkflowBlockView(
                        block = block,
                        highlighted = connectFrom == block.id,
                        onMove = { dx, dy ->
                            val idx = blocks.indexOfFirst { it.id == block.id }
                            if (idx >= 0) {
                                val b = blocks[idx]
                                // copy() делит ссылку на params (MutableMap) с оригиналом — это ОК,
                                // params здесь не меняются, трогаем только координаты.
                                blocks[idx] = b.copy(x = b.x + dx, y = b.y + dy)
                            }
                        },
                        onTap = {
                            if (connectMode) {
                                val from = connectFrom
                                if (from == null) {
                                    connectFrom = block.id
                                } else if (from != block.id) {
                                    connections.removeAll { it.fromId == from }
                                    connections.add(WorkflowConnection(from, block.id))
                                    connectFrom = null
                                }
                            } else {
                                editingBlock = block
                            }
                        },
                        onDelete = {
                            blocks.removeAll { it.id == block.id }
                            connections.removeAll { it.fromId == block.id || it.toId == block.id }
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { showAddPicker = true }, modifier = Modifier.weight(1f)) {
                Text("+ Блок")
            }
            Button(
                onClick = {
                    val wf = currentWorkflow()
                    storage.save(wf)
                    runStatus = "Выполняю..."
                    scope.launch {
                        runner.run(wf) { step ->
                            runStatus = when (step) {
                                is WorkflowRunner.StepResult.Started -> "Шаг: ${step.block.type.displayName}"
                                is WorkflowRunner.StepResult.Finished -> "Готово (${step.stepsRun} шаг(ов))"
                                is WorkflowRunner.StepResult.Failed -> "Остановился: ${step.reason}"
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Запустить сейчас")
            }
        }
    }

    if (showAddPicker) {
        AlertDialog(
            onDismissRequest = { showAddPicker = false },
            title = { Text("Добавить блок") },
            text = {
                Column {
                    BlockType.entries.forEach { type ->
                        TextButton(onClick = {
                            blocks.add(WorkflowBlock(type = type, x = 40f, y = 40f + blocks.size * 40f))
                            showAddPicker = false
                        }) { Text(type.displayName) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAddPicker = false }) { Text("Отмена") } }
        )
    }

    editingBlock?.let { block ->
        BlockParamsDialog(
            block = block,
            onDismiss = { editingBlock = null },
            onSave = { updated ->
                val idx = blocks.indexOfFirst { it.id == updated.id }
                if (idx >= 0) blocks[idx] = updated
                editingBlock = null
            }
        )
    }
}

@Composable
private fun WorkflowBlockView(
    block: WorkflowBlock,
    highlighted: Boolean,
    onMove: (Float, Float) -> Unit,
    onTap: () -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .offset { IntOffset(block.x.toInt(), block.y.toInt()) }
            .width(BLOCK_WIDTH_DP)
            .height(BLOCK_HEIGHT_DP)
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) Color(0xFFDCEFFF) else Color(0xFFF5F5F5))
            .border(1.dp, Color.Black.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .pointerInput(block.id) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onMove(dragAmount.x, dragAmount.y)
                }
            }
            .clickable { onTap() }
            .padding(8.dp)
    ) {
        Column {
            Text(block.type.displayName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            block.params.entries.firstOrNull()?.let {
                Text(it.value.take(24), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(
            onClick = onDelete,
            modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
        ) { Text("×") }
    }
}

@Composable
private fun BlockParamsDialog(block: WorkflowBlock, onDismiss: () -> Unit, onSave: (WorkflowBlock) -> Unit) {
    val values = remember { block.type.paramKeys.associateWith { mutableStateOf(block.params[it].orEmpty()) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(block.type.displayName) },
        text = {
            Column {
                if (block.type.paramKeys.isEmpty()) {
                    Text("У этого блока нет настроек.", color = Color.Gray)
                }
                block.type.paramKeys.forEachIndexed { i, key ->
                    var v by values.getValue(key)
                    OutlinedTextField(
                        value = v,
                        onValueChange = { v = it },
                        label = { Text(block.type.paramLabels.getOrElse(i) { key }) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val newParams = values.mapValues { it.value.value }.toMutableMap()
                onSave(block.copy(params = newParams))
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private val BLOCK_WIDTH_DP = 160.dp
private val BLOCK_HEIGHT_DP = 72.dp
