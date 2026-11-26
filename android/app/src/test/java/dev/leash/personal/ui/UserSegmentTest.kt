package dev.leash.personal.ui

import dev.leash.personal.ui.chat.UserSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSegmentTest {
    @Test fun plainTextIsOneSegment() {
        assertEquals(listOf(UserSegment.Text("hello")), UserSegment.parse("hello"))
        assertTrue(UserSegment.parse("").isEmpty())
    }

    @Test fun shellRunWithOutputAndExitCode() {
        val s = UserSegment.parse("<bash-input>ls &amp;&amp; echo &quot;x&quot;</bash-input><bash-stdout>a\nb</bash-stdout><bash-stderr>oops</bash-stderr><bash-exit-code>2</bash-exit-code>")
        assertEquals(UserSegment.ShellInput("ls && echo \"x\""), s[0])
        assertEquals(UserSegment.ShellOutput("a\nb", "oops", 2), s[1])
    }

    @Test fun slashCommandAndCaveatsAreCondensed() {
        val s = UserSegment.parse("<local-command-caveat>ignore</local-command-caveat><command-name>/compact</command-name><command-message>compact</command-message><command-args>now</command-args>")
        assertEquals(listOf<UserSegment>(UserSegment.Command("/compact now")), s)
    }

    @Test fun emptyShellOutputStillShowsButEmptyCommandOutputDoesNot() {
        assertEquals(listOf<UserSegment>(UserSegment.ShellOutput("", "", null)), UserSegment.parse("<bash-stdout></bash-stdout>"))
        assertTrue(UserSegment.parse("<local-command-stdout></local-command-stdout>").isEmpty())
        assertTrue(UserSegment.isContinuation("<bash-stdout>x</bash-stdout>"))
    }

    @Test fun textAroundTagsIsKept() {
        val s = UserSegment.parse("before <system-reminder>hidden</system-reminder> after")
        assertEquals(listOf(UserSegment.Text("before"), UserSegment.Text("after")), s)
    }
}
