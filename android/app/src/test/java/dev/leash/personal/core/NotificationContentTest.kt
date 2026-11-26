package dev.leash.personal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationContentTest {
    private fun opt(id: String, kind: String) = jsonObj("optionId" to id, "name" to id, "kind" to kind)

    private fun permission(options: List<kotlinx.serialization.json.JsonElement>, tool: kotlinx.serialization.json.JsonElement) = PendingRequest(
        "claude:7", "claude", "session/request_permission", "s 1", jsonObj("toolCall" to tool, "options" to options))

    @Test fun anApprovalOffersAllowOnceAndDenyNeverAlways() {
        val p = permission(
            listOf(opt("a", "allow_always"), opt("o", "allow_once"), opt("n", "reject_once")),
            jsonObj("toolCallId" to "t", "title" to "Bash", "kind" to "execute", "rawInput" to jsonObj("command" to "rm -rf build")),
        )
        val a = NotificationContent.request(p, "Claude Code", "Clean up")
        assertEquals("Claude Code needs approval", a.title)
        assertEquals("Clean up", a.subtitle)
        assertEquals("Bash\nrm -rf build", a.body)
        assertEquals("o", a.allowId)
        assertEquals("n", a.denyId)
        assertTrue(a.hasQuickAnswer)
        assertEquals("leash://session?id=s+1&agent=claude", a.url)
        assertEquals(LeashLink.Session("s 1", "claude"), LeashLink.parse(a.url))
    }

    @Test fun withoutAnAllowOnceAnyOtherAllowOptionWorksButNeverAlways() {
        assertEquals("y" to "x", NotificationContent.quickAnswers(listOf("allow_session" to "y", "allow_always" to "z", "reject_always" to "x")))
        assertEquals(null to "d", NotificationContent.quickAnswers(listOf("allow_always" to "z", "reject_once" to "d")))
        assertEquals(null to null, NotificationContent.quickAnswers(emptyList()))
    }

    @Test fun anApprovalWithOnlyAlwaysHasNoActionsAndIsAnsweredInTheChat() {
        val a = NotificationContent.request(permission(listOf(opt("z", "allow_always"), opt("d", "reject_once")), jsonObj("title" to "Edit Parser.kt")), "Antigravity", null)
        assertFalse(a.hasQuickAnswer)
        assertNull(a.denyId)
        assertNull(a.subtitle)
        assertEquals("Edit Parser.kt", a.body)
    }

    @Test fun theCommandInTheBodyIsCapped() {
        val p = permission(listOf(opt("o", "allow_once")), jsonObj("title" to "Bash", "rawInput" to jsonObj("command" to "x".repeat(900))))
        assertEquals("Bash\n" + "x".repeat(300), NotificationContent.request(p, "Claude Code", null).body)
    }

    @Test fun aQuestionOpensTheChatWithoutActions() {
        val q = PendingRequest("claude:8", "claude", "elicitation/create", "s", jsonObj("message" to "Which database?"))
        val a = NotificationContent.request(q, "Claude Code", "Migrate")
        assertEquals("Claude Code has a question", a.title)
        assertEquals("Which database?", a.body)
        assertFalse(a.hasQuickAnswer)
    }

    @Test fun aRequestWithoutAConversationOpensTheApp() {
        val q = PendingRequest("claude:8", "claude", "elicitation/create", null, jsonObj("message" to "Hello"))
        assertEquals("leash://open", NotificationContent.request(q, "Claude Code", null).url)
    }

    @Test fun turnEndsByReason() {
        fun end(r: String?) = NotificationContent.turnEnd("s", "claude", "Claude Code", "Refactor", r, 1_000, 65_000)
        assertEquals("Claude Code finished", end("end_turn")!!.title)
        assertEquals("Claude Code finished", end(null)!!.title)
        assertEquals("Claude Code hit an error", end("error")!!.title)
        assertEquals("Claude Code hit an error", end("refusal")!!.title)
        assertEquals("Claude Code stopped", end("max_tokens")!!.title)
        assertNull(end("cancelled"))
        val e = end("end_turn")!!
        assertEquals("Took 1m 4s", e.body)
        assertEquals("Refactor", e.subtitle)
        assertEquals("leash://session?id=s&agent=claude", e.url)
        assertNull(NotificationContent.turnEnd("s", "claude", "Claude Code", null, "end_turn", null, 5)!!.body)
    }

    @Test fun durationsUseAbbreviatedUnitsWithoutZeroOnes() {
        assertEquals("0s", NotificationContent.duration(0))
        assertEquals("0s", NotificationContent.duration(-5000))
        assertEquals("6s", NotificationContent.duration(6_400))
        assertEquals("1m", NotificationContent.duration(60_000))
        assertEquals("4m 5s", NotificationContent.duration(245_000))
        assertEquals("1h 3s", NotificationContent.duration(3_603_000))
        assertEquals("2h 3m 5s", NotificationContent.duration(7_385_000))
    }
}
