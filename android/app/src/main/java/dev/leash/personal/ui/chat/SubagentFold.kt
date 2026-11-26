package dev.leash.personal.ui.chat

import dev.leash.personal.core.ActivityGroup
import dev.leash.personal.core.ChatEntry
import dev.leash.personal.core.DisplayItem
import dev.leash.personal.core.MessageItem
import dev.leash.personal.core.SubagentItem
import dev.leash.personal.core.derived

/** A subagent's transcript as display rows: prompt, then its steps (one group), then its reply (ios SubagentTranscript.fold). */
fun foldSubagent(s: SubagentItem): List<DisplayItem> {
    val out = mutableListOf<DisplayItem>()
    var group: ActivityGroup? = null
    var answer: MessageItem? = null
    fun demoteAnswer() {
        val a = answer ?: return
        val g = group ?: ActivityGroup(a.id.derived(1)).also { group = it }
        g.steps += ChatEntry.Message(a)
        answer = null
    }
    fun flush() {
        group?.let { out += DisplayItem.Activity(it) }
        answer?.let { out += DisplayItem.Answer(it) }
        group = null
        answer = null
    }
    for (e in s.entries) {
        when (e) {
            is ChatEntry.Message -> when (e.item.role) {
                MessageItem.Role.User -> { flush(); out += DisplayItem.User(e.item) }
                MessageItem.Role.Agent -> { demoteAnswer(); answer = e.item }
                MessageItem.Role.Thought -> {
                    demoteAnswer()
                    val g = group ?: ActivityGroup(e.item.id).also { group = it }
                    g.steps += e
                }
            }
            is ChatEntry.Tool, is ChatEntry.Subagent -> {
                demoteAnswer()
                val g = group ?: ActivityGroup(e.id).also { group = it }
                g.steps += e
            }
            is ChatEntry.Plan -> {
                val g = group ?: ActivityGroup(e.item.id).also { group = it }
                g.planEntries = e.item.entries
            }
            is ChatEntry.Notice -> { flush(); out += DisplayItem.Notice(e.item) }
            is ChatEntry.Stats -> {}
        }
    }
    if (s.isRunning) {
        val g = group ?: ActivityGroup(s.id.derived(3)).also { group = it }
        g.live = true
    }
    flush()
    return out
}
