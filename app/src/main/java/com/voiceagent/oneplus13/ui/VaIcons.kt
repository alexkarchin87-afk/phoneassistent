package com.voiceagent.oneplus13.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Единый набор тонких контурных иконок (24×24, штрих 1.8) — в одном стиле,
 * без зависимости от material-icons-extended. Цвет задаётся через Icon(tint = …).
 */
object VaIcons {
    val Mic by lazy {
        line("Mic",
            "M12 3 a3 3 0 0 0 -3 3 v6 a3 3 0 0 0 6 0 V6 a3 3 0 0 0 -3 -3 z",
            "M5 11 a7 7 0 0 0 14 0", "M12 18 v3", "M9 21 h6")
    }
    val ArrowUp by lazy { line("ArrowUp", "M12 19 V5", "M5 12 l7 -7 l7 7") }
    val Sliders by lazy {
        line("Sliders",
            "M4 7 h9", "M19 7 h1", "M4 17 h1", "M11 17 h9",
            circle(16f, 7f, 2.5f), circle(8f, 17f, 2.5f))
    }
    val Back by lazy { line("Back", "M19 12 H6", "M11 6 l-6 6 l6 6") }
    val Close by lazy { line("Close", "M6 6 l12 12", "M18 6 L6 18") }
    val Chevron by lazy { line("Chevron", "M9 6 l6 6 l-6 6") }
    val History by lazy { line("History", circle(12f, 12f, 9f), "M12 7 v5 l3 2") }
    val Chat by lazy {
        line("Chat", "M5 5 h14 a2 2 0 0 1 2 2 v8 a2 2 0 0 1 -2 2 h-8 l-5 4 v-4 h-1 a2 2 0 0 1 -2 -2 V7 a2 2 0 0 1 2 -2 z")
    }
    val Accessibility by lazy {
        line("Accessibility", circle(12f, 4.5f, 1.6f), "M5 8.5 l7 1.5 l7 -1.5", "M12 10 v4", "M9 21 l3 -7 l3 7")
    }
    val Bell by lazy { line("Bell", "M6 16 v-5 a6 6 0 0 1 12 0 v5 l2 2 H4 z", "M10 21 h4") }
    val Battery by lazy {
        line("Battery", "M5 7 h12 a2 2 0 0 1 2 2 v6 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 V9 a2 2 0 0 1 2 -2 z", "M22 11 v2", "M7 10 v4")
    }
    val Key by lazy { line("Key", circle(7.5f, 15.5f, 3.5f), "M10 13 l9 -9", "M16 7 l3 3") }
    val Flow by lazy {
        line("Flow", circle(6f, 6f, 2f), circle(6f, 18f, 2f), circle(18f, 12f, 2f), "M6 8 v8", "M8 12 h8")
    }
    val Layers by lazy { line("Layers", "M12 3 l9 5 l-9 5 l-9 -5 z", "M3 13 l9 5 l9 -5") }
    val Monitor by lazy {
        line("Monitor", "M4 5 h16 a1 1 0 0 1 1 1 v10 a1 1 0 0 1 -1 1 H4 a1 1 0 0 1 -1 -1 V6 a1 1 0 0 1 1 -1 z", "M8 21 h8", "M12 17 v4")
    }
    val Power by lazy { line("Power", "M12 3 v9", "M7 6.5 a7 7 0 1 0 10 0") }
    val Trash by lazy { line("Trash", "M4 7 h16", "M9 7 V4 h6 v3", "M6 7 l1 13 h10 l1 -13") }
    val Copy by lazy { line("Copy", "M9 9 h11 v11 H9 z", "M5 15 V4 h11") }
    val Upload by lazy { line("Upload", "M12 15 V4", "M7 9 l5 -5 l5 5", "M5 20 h14") }
    val Download by lazy { line("Download", "M12 4 v11", "M7 10 l5 5 l5 -5", "M5 20 h14") }
    val Bookmark by lazy { line("Bookmark", "M7 4 h10 v17 l-5 -4 l-5 4 z") }
    val Alert by lazy { line("Alert", "M12 3 l10 18 H2 z", "M12 10 v4.5", "M12 17.6 v0.4") }
    val Check by lazy { line("Check", "M5 12 l5 5 l9 -9") }
    val Plus by lazy { line("Plus", "M12 5 v14", "M5 12 h14") }
    val File by lazy { line("File", "M6 3 h8 l4 4 v14 H6 z", "M14 3 v4 h4") }
    val Pulse by lazy { line("Pulse", "M3 12 h4 l3 -8 l4 16 l3 -8 h4") }
    val Info by lazy { line("Info", circle(12f, 12f, 9f), "M12 11 v5", "M12 7.6 v0.4") }
    val Volume by lazy {
        line("Volume", "M11 5 L6 9 H3 v6 h3 l5 4 z", "M15.5 8.5 a5 5 0 0 1 0 7", "M18.5 5.5 a9 9 0 0 1 0 13")
    }
    val Share by lazy {
        line("Share", circle(6f, 12f, 2.5f), circle(18f, 6f, 2.5f), circle(18f, 18f, 2.5f), "M8.3 10.9 l7.4 -3.8", "M8.3 13.1 l7.4 3.8")
    }
}

private fun circle(cx: Float, cy: Float, r: Float): String {
    val d = r * 2
    return "M${cx - r} $cy a$r $r 0 1 0 $d 0 a$r $r 0 1 0 ${-d} 0 z"
}

private fun line(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        paths.forEach { d ->
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
    }.build()
