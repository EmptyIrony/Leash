package dev.leash.personal.core

/** This build has no Tailnet AAR (no NDK on the build host to run gomobile with): Settings says so. */
object NativeTailnet {
    fun create(): TailnetNode? = null
}
