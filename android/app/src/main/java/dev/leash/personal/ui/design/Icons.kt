package dev.leash.personal.ui.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardCommandKey
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Every glyph the app uses, named by its iOS meaning (DESIGN.md §10), so a screen never picks an icon ad hoc.
 * Outline by default (Material Outlined / Rounded), filled only where iOS uses `.fill`.
 */
object LeashIcons {
    // navigation and chrome
    val back = Icons.AutoMirrored.Rounded.ArrowBack
    val more = Icons.Rounded.MoreHoriz
    val chevronRight = Icons.AutoMirrored.Rounded.KeyboardArrowRight
    val chevronDown = Icons.Rounded.KeyboardArrowDown
    val close = Icons.Rounded.Close
    val check = Icons.Rounded.Check
    val search = Icons.Rounded.Search
    val settings = Icons.Outlined.Settings
    val server = Icons.Outlined.Dns
    val squareAndPencil: ImageVector by lazy { squareAndPencilVector() }
    val refresh = Icons.Rounded.Refresh
    val scrollDown = Icons.Rounded.ArrowDownward

    // composer
    val plus = Icons.Rounded.Add
    val send = Icons.Rounded.ArrowUpward
    val stop = Icons.Rounded.Stop
    val run = Icons.AutoMirrored.Rounded.KeyboardReturn
    val terminal = Icons.Rounded.Terminal
    val sideQuestion = Icons.Outlined.Forum
    val photo = Icons.Outlined.Image
    val file = Icons.AutoMirrored.Outlined.InsertDriveFile
    val effort = Icons.Outlined.Speed
    val modeAsk = Icons.Outlined.PanTool
    val modeAuto = Icons.Outlined.AutoFixHigh
    val modePlan = Icons.AutoMirrored.Outlined.Assignment
    val modeEdits = Icons.Outlined.Edit
    val modeBypass = Icons.Outlined.GppMaybe
    val turbo = Icons.Rounded.Bolt
    val context = Icons.AutoMirrored.Outlined.Notes
    val command = Icons.Rounded.KeyboardCommandKey

    // states
    val needsYou = Icons.Rounded.PanTool
    val locked = Icons.Rounded.Lock
    val done = Icons.Outlined.CheckCircle
    val doneFilled = Icons.Rounded.CheckCircle
    val failed = Icons.Outlined.ErrorOutline
    val stopped = Icons.Outlined.StopCircle
    val warning = Icons.Rounded.Warning
    val info = Icons.Outlined.Info
    val waiting = Icons.Outlined.HourglassEmpty
    val queued = Icons.Outlined.Schedule
    val question = Icons.AutoMirrored.Outlined.HelpOutline
    val radioOff = Icons.Rounded.RadioButtonUnchecked
    val radioOn = Icons.Rounded.RadioButtonChecked
    val checkboxOff = Icons.Rounded.CheckBoxOutlineBlank
    val checkboxOn = Icons.Rounded.CheckBox

    // tools (ToolIcon on iOS)
    val read = Icons.Outlined.Description
    val edit = Icons.Outlined.Edit
    val delete = Icons.Outlined.Delete
    val move = Icons.AutoMirrored.Outlined.DriveFileMove
    val think = Icons.Outlined.Psychology
    val fetch = Icons.Outlined.Language
    val tool = Icons.Outlined.Build
    val note = Icons.Outlined.ChatBubbleOutline
    val subagent = Icons.AutoMirrored.Rounded.CallSplit
    val diff = Icons.Outlined.Difference

    // places and actions
    val folder = Icons.Outlined.Folder
    val general = Icons.Outlined.Inbox
    val copy = Icons.Outlined.ContentCopy
    val paste = Icons.Outlined.ContentPaste
    val scan = Icons.Rounded.QrCodeScanner
    val computer = Icons.Outlined.DesktopWindows
    val openExternal = Icons.AutoMirrored.Outlined.OpenInNew
    val show = Icons.Outlined.Visibility
    val hide = Icons.Outlined.VisibilityOff

    /** The glyph for a tool call kind (ios ToolIcon.name). */
    fun forTool(kind: String?): ImageVector = when (kind) {
        "read" -> read
        "edit" -> edit
        "delete" -> delete
        "move" -> move
        "search" -> search
        "execute" -> terminal
        "think" -> think
        "fetch" -> fetch
        else -> tool
    }

    /** SF Symbols' square.and.pencil (new conversation): a rounded square open at the top-end with a pencil. */
    private fun squareAndPencilVector() = ImageVector.Builder("SquareAndPencil", 24.dp, 24.dp, 24f, 24f, autoMirror = true).apply {
        path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(11f, 4.5f)
            horizontalLineTo(7.5f)
            curveTo(5.6f, 4.5f, 4.5f, 5.6f, 4.5f, 7.5f)
            verticalLineTo(16.5f)
            curveTo(4.5f, 18.4f, 5.6f, 19.5f, 7.5f, 19.5f)
            horizontalLineTo(16.5f)
            curveTo(18.4f, 19.5f, 19.5f, 18.4f, 19.5f, 16.5f)
            verticalLineTo(13f)
        }
        path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(17.6f, 3.9f)
            lineTo(20.1f, 6.4f)
            lineTo(12.2f, 14.3f)
            lineTo(9.2f, 14.8f)
            lineTo(9.7f, 11.8f)
            close()
        }
    }.build()
}
