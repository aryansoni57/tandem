// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Glyphs from the mockup that material-icons-core does not contain. material-icons-extended is
 * not in the offline Gradle cache, so these are drawn as simple 24 dp line or solid icons. Names
 * that exist in core (Search, Send, Share, Lock, ...) are used from `Icons` directly.
 * Icons are tinted by `Icon(tint = ...)`, so the vector color itself does not matter.
 */
object TandemIcons {
    val Folder = glyph("Folder", stroke = listOf("M3 6h7l2 2h9v11H3z"))
    val Laptop = glyph("Laptop", stroke = listOf("M4 6h16v10H4z", "M2 19h20"))
    val Smartphone = glyph("Smartphone", stroke = listOf("M7 2.5h10v19H7z", "M11 18.5h2"))
    val Tablet = glyph("Tablet", stroke = listOf("M5.5 2.5h13v19h-13z", "M11 18.5h2"))
    val Desktop = glyph("Desktop", stroke = listOf("M3 4h18v12H3z", "M9 20h6M12 16v4"))
    val Wifi =
        glyph(
            "Wifi",
            stroke = listOf("M2 9a15 15 0 0 1 20 0", "M5.5 12.5a10 10 0 0 1 13 0", "M9 16a5 5 0 0 1 6 0"),
            fill = listOf(circle(12f, 19.5f, 1.5f)),
        )
    val Sync =
        glyph("Sync", stroke = listOf("M20 11a8 8 0 0 0-14-4", "M4 13a8 8 0 0 0 14 4", "M18 4v4h-4", "M6 20v-4h4"))
    val SyncAlt = glyph("SyncAlt", stroke = listOf("M3 8h13l-3-3", "M21 16H8l3 3"))
    val MusicNote =
        glyph(
            "MusicNote",
            stroke = listOf("M9 18V5l11-2v13"),
            fill = listOf(circle(6.5f, 18f, 2.5f), circle(17.5f, 16f, 2.5f)),
        )
    val GraphicEq = glyph("GraphicEq", stroke = listOf("M4 10v4", "M8 6v12", "M12 3v18", "M16 8v8", "M20 10v4"))
    val Pause = glyph("Pause", fill = listOf("M6 5h4v14H6z", "M14 5h4v14h-4z"))
    val SkipPrevious = glyph("SkipPrevious", fill = listOf("M6 6h2v12H6z", "M9.5 12L18 6v12z"))
    val SkipNext = glyph("SkipNext", fill = listOf("M16 6h2v12h-2z", "M6 6l8.5 6L6 18z"))
    val Keyboard =
        glyph(
            "Keyboard",
            stroke = listOf("M2 6h20v12H2z", "M6 10h0.01", "M10 10h0.01", "M14 10h0.01", "M18 10h0.01", "M7 14h10"),
        )
    val Mouse =
        glyph(
            "Mouse",
            stroke = listOf("M7 2.5h10a2 2 0 0 1 2 2v15a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-15a2 2 0 0 1 2-2z", "M12 2.5v7"),
        )
    val Swipe = glyph("Swipe", stroke = listOf("M4 12h16", "M8 8l-4 4 4 4", "M16 8l4 4-4 4"))
    val Power = glyph("Power", stroke = listOf("M12 3v9", "M6.5 6.5a8 8 0 1 0 11 0"))
    val Timer = glyph("Timer", stroke = listOf(circle(12f, 13f, 8f), "M12 9v4l2.5 2.5", "M9 2.5h6"))
    val VolumeDown = glyph("VolumeDown", stroke = listOf("M3 9h4l5-4v14l-5-4H3z", "M16 9a4 4 0 0 1 0 6"))
    val VolumeUp =
        glyph("VolumeUp", stroke = listOf("M3 9h4l5-4v14l-5-4H3z", "M16 9a4 4 0 0 1 0 6", "M19 6a8 8 0 0 1 0 12"))
    val SpeakerGroup = glyph("SpeakerGroup", stroke = listOf("M6 2.5h12v19H6z", circle(12f, 14f, 3.5f), "M10 6h4"))
    val Link =
        glyph(
            "Link",
            stroke =
                listOf(
                    "M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1",
                    "M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1",
                ),
        )
    val History = glyph("History", stroke = listOf("M3.5 12a8.5 8.5 0 1 0 2.5-6.2", "M3 4v5h5", "M12 7.5V12l3 2"))
    val UploadFile =
        glyph(
            "UploadFile",
            stroke =
                listOf(
                    "M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z",
                    "M14 3v6h6",
                    "M12 17v-6",
                    "M9.5 13.5L12 11l2.5 2.5",
                ),
        )
    val Description = glyph("Description", stroke = listOf("M14 3H6v18h12V9z", "M14 3v6h6", "M9 13h6", "M9 17h6"))
    val Image =
        glyph(
            "Image",
            stroke = listOf("M3 4h18v16H3z", "M3 16l5-5 4 4 3-3 6 6"),
            fill = listOf(circle(8.5f, 8.5f, 1.5f)),
        )
    val ContentCopy = glyph("ContentCopy", stroke = listOf("M9 9h11v11H9z", "M5 15H4V4h11v1"))
    val ContentPasteGo =
        glyph("ContentPasteGo", stroke = listOf("M9 4.5V3h6v1.5", "M7 5H5v16h9", "M13 13h6", "M16 10l3 3-3 3"))
    val DoNotDisturb = glyph("DoNotDisturb", stroke = listOf(circle(12f, 12f, 9f), "M7.5 12h9"))
    val ChatBubble = glyph("ChatBubble", stroke = listOf("M4 4h16v12H8l-4 4z", "M8 9h8", "M8 12h5"))
    val AccountBalance =
        glyph(
            "AccountBalance",
            stroke = listOf("M3 10l9-6 9 6", "M5 10v9", "M9.5 10v9", "M14.5 10v9", "M19 10v9", "M3 21h18"),
        )
    val PauseCircle =
        glyph("PauseCircle", stroke = listOf(circle(12f, 12f, 9f)), fill = listOf("M9 8h2v8H9z", "M13 8h2v8h-2z"))
    val DarkMode = glyph("DarkMode", fill = listOf("M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"))
    val Backup = glyph("Backup", stroke = listOf("M12 3v12", "M7 10l5 5 5-5", "M4 19h16"))
    val ScreenshotMonitor =
        glyph(
            "ScreenshotMonitor",
            stroke = listOf("M3 4h18v12H3z", "M8 20h8", "M6 8V6h2", "M16 6h2v2", "M18 13v2h-2", "M8 15H6v-2"),
        )
    val Slideshow = glyph("Slideshow", stroke = listOf("M3 4h18v12H3z", "M8 20h8", "M12 16v4", "M10 8.5v5l4.5-2.5z"))
    val AdsClick =
        glyph("AdsClick", stroke = listOf(circle(12f, 12f, 6f), "M12 2v4", "M12 18v4", "M2 12h4", "M18 12h4"))
    val SmartDisplay = glyph("SmartDisplay", stroke = listOf("M3 5h18v11H3z", "M8 20h8", "M10 8.5v5l4.5-2.5z"))
    val Brightness =
        glyph(
            "Brightness",
            stroke =
                listOf(
                    circle(12f, 12f, 4f), "M12 2v2", "M12 20v2", "M2 12h2", "M20 12h2",
                    "M4.9 4.9l1.4 1.4", "M17.7 17.7l1.4 1.4", "M4.9 19.1l1.4-1.4", "M17.7 6.3l1.4-1.4",
                ),
        )
    val RadioChecked =
        glyph("RadioChecked", stroke = listOf(circle(12f, 12f, 8f)), fill = listOf(circle(12f, 12f, 4.5f)))
    val RadioUnchecked = glyph("RadioUnchecked", stroke = listOf(circle(12f, 12f, 8f)))
    val Bluetooth = glyph("Bluetooth", stroke = listOf("M7 7l10 10-5 5V2l5 5L7 17"))
    val PhotoLibrary = glyph("PhotoLibrary", stroke = listOf("M7 2.5h14v14H7z", "M3 6.5v15h15"))
    val Tune =
        glyph(
            "Tune",
            stroke =
                listOf(
                    "M4 6h9", "M17 6h3", "M4 12h3", "M11 12h9", "M4 18h11", "M19 18h2",
                    circle(
                        14f,
                        6f,
                        2f,
                    ),
                    circle(9f, 12f, 2f), circle(17f, 18f, 2f),
                ),
        )

    private fun circle(
        cx: Float,
        cy: Float,
        r: Float,
    ): String = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun glyph(
        name: String,
        stroke: List<String> = emptyList(),
        fill: List<String> = emptyList(),
    ): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            stroke.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            fill.forEach { d -> addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black)) }
        }.build()
}
