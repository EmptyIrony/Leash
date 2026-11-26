package dev.leash.personal.ui

import dev.leash.personal.core.PendingRequest
import dev.leash.personal.core.jsonObj
import dev.leash.personal.core.j
import dev.leash.personal.core.string
import dev.leash.personal.core.array
import dev.leash.personal.ui.chat.ElicitationAnswers
import dev.leash.personal.ui.chat.ElicitationSpec
import dev.leash.personal.ui.chat.FieldKind
import dev.leash.personal.ui.chat.permissionOptions
import dev.leash.personal.ui.chat.splitPermissionOptions
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElicitationTest {
    private fun choice(v: String, t: String = v, d: String? = null) = jsonObj("const" to v, "title" to t, "description" to d).let { if (d == null) jsonObj("const" to v, "title" to t) else it }

    private fun spec(props: Map<String, Any?>, required: List<String> = emptyList(), message: String? = "Pick") =
        ElicitationSpec.of(jsonObj("message" to message, "requestedSchema" to jsonObj("type" to "object", "required" to required, "properties" to props)))

    @Test fun singleChoiceFitsTheTrayUpToFour() {
        val few = spec(mapOf("question_0" to jsonObj("type" to "string", "oneOf" to listOf(choice("a"), choice("b"), choice("c"), choice("d")))))
        assertTrue(few.fitsTray)
        val many = spec(mapOf("question_0" to jsonObj("type" to "string", "oneOf" to (1..5).map { choice("v$it") })))
        assertFalse(many.fitsTray)
    }

    @Test fun textAndMultipleQuestionsOpenTheSheet() {
        assertFalse(spec(mapOf("q" to jsonObj("type" to "string"))).fitsTray)
        val two = spec(mapOf("question_0" to jsonObj("enum" to listOf("a", "b")), "question_1" to jsonObj("enum" to listOf("c", "d"))))
        assertEquals(2, two.questions.size)
        assertFalse(two.fitsTray)
    }

    @Test fun customFieldPairsWithItsQuestionAndIsNotAQuestion() {
        val s = spec(mapOf("question_0" to jsonObj("enum" to listOf("a", "b")), "question_0_custom" to jsonObj("type" to "string")))
        assertEquals(listOf("question_0"), s.questions.map { it.name })
        assertEquals("question_0_custom", s.custom(s.questions[0])?.name)
        assertTrue(s.fitsTray)
    }

    @Test fun customWithoutItsQuestionIsAQuestionOfItsOwn() {
        val s = spec(mapOf("lonely_custom" to jsonObj("type" to "string")))
        assertEquals(listOf("lonely_custom"), s.questions.map { it.name })
    }

    @Test fun requiredFieldsComeFirstThenNaturalOrder() {
        val s = spec(
            mapOf("question_10" to jsonObj("type" to "string"), "question_2" to jsonObj("type" to "string"), "zeta" to jsonObj("type" to "string")),
            required = listOf("zeta"),
        )
        assertEquals(listOf("zeta", "question_2", "question_10"), s.fields.map { it.name })
    }

    @Test fun kindsAreRecognised() {
        val s = spec(mapOf(
            "b" to jsonObj("type" to "boolean"), "n" to jsonObj("type" to "integer"),
            "m" to jsonObj("type" to "array", "items" to jsonObj("enum" to listOf("x", "y"))),
            "s" to jsonObj("anyOf" to listOf(choice("p", "Pee", "desc"))),
        ))
        val kinds = s.fields.associate { it.name to it.kind }
        assertEquals(FieldKind.Bool, kinds["b"])
        assertEquals(FieldKind.Number, kinds["n"])
        assertEquals(2, (kinds["m"] as FieldKind.Multi).choices.size)
        val single = (kinds["s"] as FieldKind.Single).choices.single()
        assertEquals("Pee", single.title)
        assertEquals("desc", single.detail)
    }

    @Test fun urlModeNeedsNoQuestions() {
        val s = ElicitationSpec.of(jsonObj("mode" to "url", "url" to "https://example.com/auth", "message" to "Sign in"))
        assertEquals("https://example.com/auth", s.url)
        assertTrue(s.fitsTray)
        assertTrue(s.questions.isEmpty())
    }

    @Test fun singleChoiceTogglesAndReplaces() {
        val s = spec(mapOf("q" to jsonObj("enum" to listOf("a", "b"))))
        val f = s.questions[0]
        val (a, b) = (f.kind as FieldKind.Single).choices
        var ans = ElicitationAnswers()
        assertFalse(ans.hasAnswer)
        ans = ans.toggle(f, a)
        assertTrue(ans.isSelected(f, a)); assertTrue(ans.hasAnswer)
        ans = ans.toggle(f, b)
        assertFalse(ans.isSelected(f, a)); assertTrue(ans.isSelected(f, b))
        ans = ans.toggle(f, b)
        assertFalse(ans.hasAnswer)
    }

    @Test fun multiChoiceCollectsSortedAndContentHoldsOnlyAnswers() {
        val s = spec(mapOf("m" to jsonObj("type" to "array", "items" to jsonObj("enum" to listOf("x", "y", "z")))))
        val f = s.questions[0]
        val cs = (f.kind as FieldKind.Multi).choices
        var ans = ElicitationAnswers()
        ans = ans.toggle(f, cs[2]).toggle(f, cs[0]).toggle(f, cs[1]).toggle(f, cs[1])
        assertEquals(listOf("x", "z"), ans.content(s)["m"].array!!.map { it.string })
    }

    @Test fun contentFillsDefaultsOfFieldsLeftAlone() {
        val s = spec(mapOf("q" to jsonObj("enum" to listOf("a", "b")), "flag" to jsonObj("type" to "boolean", "default" to true), "note" to jsonObj("type" to "string")))
        val f = s.questions.first { it.name == "q" }
        val ans = ElicitationAnswers().toggle(f, (f.kind as FieldKind.Single).choices[1])
        val c = ans.content(s)
        assertEquals("b", c["q"].string)
        assertEquals(JsonPrimitive(true), c["flag"])
        assertNull(c["note"])
    }

    @Test fun emptyStringIsNoAnswer() {
        val s = spec(mapOf("t" to jsonObj("type" to "string")))
        val ans = ElicitationAnswers().with("t", j(""))
        assertFalse(ans.hasAnswer)
        assertTrue(ans.content(s).isEmpty())
        assertTrue(ans.with("t", j("hello")).hasAnswer)
    }

    @Test fun permissionButtonsOrderDenyAlwaysAllow() {
        val req = PendingRequest("k", "claude", "session/request_permission", "s", jsonObj("options" to listOf(
            jsonObj("optionId" to "allow", "name" to "Allow", "kind" to "allow_once"),
            jsonObj("optionId" to "always", "name" to "Always allow", "kind" to "allow_always"),
            jsonObj("optionId" to "deny", "name" to "Deny", "kind" to "reject_once"),
        )))
        val b = splitPermissionOptions(permissionOptions(req.params))
        assertEquals(listOf("deny", "always", "allow"), b.all.map { it.id })
        assertEquals(listOf("allow"), b.allow.map { it.id })
    }

    @Test fun optionsWithoutIdAreDropped() {
        val o = permissionOptions(jsonObj("options" to listOf(jsonObj("name" to "x"), jsonObj("optionId" to "ok"))))
        assertEquals(listOf("ok"), o.map { it.id })
        assertEquals("ok", o[0].name)
    }
}
