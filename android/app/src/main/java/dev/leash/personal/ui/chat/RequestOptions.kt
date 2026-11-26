package dev.leash.personal.ui.chat

import dev.leash.personal.core.array
import dev.leash.personal.core.get
import dev.leash.personal.core.string
import kotlinx.serialization.json.JsonElement

/** One button of a permission request. */
data class PermOption(val id: String, val name: String, val kind: String)

/** The request's options in the order the tray shows them: deny first, then "always", then the allow buttons (the primary action). */
data class PermissionButtons(val deny: List<PermOption>, val always: List<PermOption>, val allow: List<PermOption>) {
    val all: List<PermOption> get() = deny + always + allow
}

fun permissionOptions(params: JsonElement): List<PermOption> =
    (params["options"].array ?: emptyList()).mapNotNull { o ->
        val id = o["optionId"].string ?: return@mapNotNull null
        PermOption(id, o["name"].string ?: id, o["kind"].string ?: "")
    }

fun splitPermissionOptions(opts: List<PermOption>) = PermissionButtons(
    deny = opts.filter { !it.kind.startsWith("allow") },
    always = opts.filter { it.kind == "allow_always" },
    allow = opts.filter { it.kind.startsWith("allow") && it.kind != "allow_always" },
)
