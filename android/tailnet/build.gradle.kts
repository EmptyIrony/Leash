import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

// The built-in Tailscale node (Go, tsnet) as an AAR: `gomobile bind` over the Go sources, with a pinned Go toolchain
// downloaded into a cache that survives `clean` (we have no shell on the Windows build host, so Gradle does it all).
//
// Go sources: the single source of truth is ../../tailnet. The remote build only syncs android/, so
// `tailnet/sync-go.sh` copies them into tailnet/go/ (git-ignored) first; with no go/ the repo's own ../../tailnet is used.
// No prebuilt binaries are committed.

val goVer = "1.27.4"
// from https://go.dev/dl/?mode=json
val goArchives = mapOf(
    "windows-amd64" to ("go$goVer.windows-amd64.zip" to "178ab98950ac69ab25069307dc6ade79f3816e414cce95b4da166a115cde1e7e"),
)
val androidApiLevel = 29
/** The NDK gomobile compiles with (cgo), side by side under <sdk>/ndk. `sdkmanager "ndk;$ndkVersion"` installs it. */
val ndkVersion = "27.2.12479018"

val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val platformKey = (if (isWindows) "windows" else System.getProperty("os.name").lowercase().substringBefore(' ')) + "-" +
    System.getProperty("os.arch").lowercase().let { if (it == "x86_64" || it == "amd64") "amd64" else it }

/** Survives `clean` (which only removes build/): the toolchain and the Go caches are minutes of work. */
val cacheDir = rootProject.layout.projectDirectory.dir(".gradle/tailnet-toolchain")
val goSrcDir = layout.projectDirectory.dir("go").takeIf { it.file("go.mod").asFile.exists() }
    ?: layout.projectDirectory.dir("../../tailnet")

fun sdkDir(): File? {
    System.getenv("ANDROID_HOME")?.let { return File(it) }
    System.getenv("ANDROID_SDK_ROOT")?.let { return File(it) }
    val lp = rootProject.file("local.properties")
    if (lp.exists()) {
        val props = java.util.Properties().apply { lp.inputStream().use { load(it) } }
        props.getProperty("sdk.dir")?.let { return File(it) }
    }
    return null
}

/** The pinned NDK (or ANDROID_NDK_HOME), or null when it is not installed. */
fun ndkDir(): File? {
    System.getenv("ANDROID_NDK_HOME")?.let { if (File(it).isDirectory) return File(it) }
    return sdkDir()?.resolve("ndk/$ndkVersion")?.takeIf { it.isDirectory }
}

abstract class InstallGo : DefaultTask() {
    @get:Input abstract val fileName: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:Input abstract val version: Property<String>
    @get:OutputDirectory abstract val goRoot: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations
    @get:Inject abstract val archives: ArchiveOperations

    private fun File.sha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        inputStream().use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @TaskAction
    fun install() {
        val zip = File(temporaryDir, fileName.get())
        if (!zip.exists() || zip.sha256() != sha256.get()) {
            val url = "https://go.dev/dl/${fileName.get()}"
            logger.lifecycle("Downloading $url")
            java.net.URI(url).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        }
        val got = zip.sha256()
        check(got == sha256.get()) { "sha256 of ${fileName.get()} is $got, expected ${sha256.get()}" }
        val out = goRoot.get().asFile
        fs.delete { delete(out) }
        out.mkdirs()
        logger.lifecycle("Unpacking Go ${version.get()}")
        fs.copy { from(archives.zipTree(zip)); into(out) }   // the archive's top directory is go/
        check(File(out, "go/bin/" + if (System.getProperty("os.name").lowercase().contains("windows")) "go.exe" else "go").exists()) { "no go binary in the archive" }
    }
}

/** Runs a command with the pinned Go on PATH and every Go cache inside [cacheDir]; the output goes to a log that is shown when it fails. */
abstract class GoTask : DefaultTask() {
    @get:Internal abstract val goHome: DirectoryProperty
    @get:Internal abstract val cache: DirectoryProperty
    /** the Go sources as they are in the repo (the task's declared inputs, never touched by Go) */
    @get:Internal abstract val sourceDir: DirectoryProperty
    /** a private copy the Go tools run in: with GOFLAGS=-mod=mod they rewrite go.sum, which must not change the inputs */
    @get:Internal abstract val workDir: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations
    @get:Internal abstract val sdk: Property<String>
    @get:Internal abstract val ndk: Property<String>
    @get:Inject abstract val exec: ExecOperations

    protected fun env(): Map<String, String> {
        val root = goHome.get().asFile.resolve("go")
        val bin = cache.get().asFile.resolve("bin")
        val sep = File.pathSeparator
        val systemEnv = System.getenv()
        val pathKey = systemEnv.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
        val e = LinkedHashMap(systemEnv)
        e[pathKey] = listOf(root.resolve("bin"), bin, File(javaHome, "bin")).joinToString(sep) { it.absolutePath } + sep + (systemEnv[pathKey] ?: "")
        e["GOROOT"] = root.absolutePath
        e["GOPATH"] = cache.get().asFile.resolve("gopath").absolutePath
        e["GOCACHE"] = cache.get().asFile.resolve("gocache").absolutePath
        e["GOBIN"] = bin.absolutePath
        e["GOFLAGS"] = "-mod=mod"
        e["GOTOOLCHAIN"] = "local"
        e["GOTELEMETRY"] = "off"
        if (sdk.isPresent) { e["ANDROID_HOME"] = sdk.get(); e["ANDROID_SDK_ROOT"] = sdk.get() }
        if (ndk.isPresent) e["ANDROID_NDK_HOME"] = ndk.get()
        return e
    }

    /** An absolute path: the daemon's own PATH, not the environment passed to the process, is what resolves a bare name on Windows. */
    private fun exePath(name: String): String {
        val ext = if (System.getProperty("os.name").lowercase().contains("windows")) ".exe" else ""
        val candidates = listOf(goHome.get().asFile.resolve("go/bin/$name$ext"), cache.get().asFile.resolve("bin/$name$ext"))
        return (candidates.firstOrNull { it.exists() } ?: throw GradleException("$name$ext not found in " + candidates.joinToString())).absolutePath
    }

    protected fun stage() {
        fs.sync {
            from(sourceDir) { include("*.go", "go.mod", "go.sum", "build-tags.txt"); exclude("*_test.go") }
            into(workDir)
        }
    }

    protected fun run(vararg cmd: String) {
        val log = File(temporaryDir, "last.log")
        val result = log.outputStream().use { out ->
            exec.exec {
                workingDir = workDir.get().asFile
                environment(env())
                commandLine(listOf(exePath(cmd[0])) + cmd.drop(1))
                standardOutput = out; errorOutput = out
                isIgnoreExitValue = true
            }
        }
        val text = log.readText()
        if (result.exitValue != 0) throw GradleException("${cmd.take(3).joinToString(" ")} failed (${result.exitValue}):\n" + text.takeLast(6000))
        logger.info(text.takeLast(3000))
    }
}

abstract class InstallGomobile : GoTask() {
    @get:Input abstract val goVersion: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val modFiles: ConfigurableFileCollection
    @get:OutputFile abstract val gomobileBin: RegularFileProperty
    @TaskAction
    fun install() {
        // pinned by go.mod/go.sum (tools.go); `gomobile init` is not run: it would replace the pinned gobind with @latest
        stage()
        run("go", "install", "golang.org/x/mobile/cmd/gomobile", "golang.org/x/mobile/cmd/gobind")
    }
}

abstract class BindTailnet : GoTask() {
    @get:Input abstract val goVersion: Property<String>
    @get:Input abstract val androidApi: Property<Int>
    /** gomobile -target: android/amd64 (debug, the emulator) or android/arm64 (release) */
    @get:Input abstract val target: Property<String>
    @get:Input abstract val ndkName: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val gomobileBin: RegularFileProperty
    @get:OutputFile abstract val aar: RegularFileProperty
    @TaskAction
    fun bind() {
        stage()
        val tags = workDir.get().file("build-tags.txt").asFile.readText().replace("\n", "").trim()
        aar.get().asFile.parentFile.mkdirs()
        run(
            "gomobile", "bind", "-target", target.get(), "-androidapi", androidApi.get().toString(),
            "-javapkg", "dev.leash.personal", "-tags", tags, "-trimpath", "-ldflags", "-s -w",
            "-o", aar.get().asFile.absolutePath, ".",
        )
    }
}

/**
 * Whether the app links the AAR: only when the NDK is there to build it (override with -Ptailnet=on / off). Without it the app
 * still builds, and Settings says built-in Tailscale is not included.
 */
extra["tailnetLinked"] = when (providers.gradleProperty("tailnet").orNull) {
    "on" -> true
    "off" -> false
    else -> ndkDir() != null
}
extra["tailnetAarDebug"] = layout.buildDirectory.file("outputs/tailnet-debug.aar")
extra["tailnetAarRelease"] = layout.buildDirectory.file("outputs/tailnet-release.aar")
extra["ndkVersion"] = ndkVersion

val exe = if (isWindows) ".exe" else ""
val goInstallDir = cacheDir.dir("go-$goVer")
val gomobileFile = cacheDir.file("bin/gomobile$exe")

/** Fails early (it runs before the Go download when both are scheduled) when the host cannot build the AAR. */
val checkTailnetHost = tasks.register("checkTailnetHost") {
    doLast {
        val s = sdkDir() ?: throw GradleException("BLOCKED: no Android SDK (ANDROID_HOME / local.properties sdk.dir)")
        if (ndkDir() == null) throw GradleException("BLOCKED: no Android NDK under $s/ndk. Install it with: sdkmanager \"ndk;$ndkVersion\"")
        if (!File(goSrcDir.asFile, "tailnet.go").exists()) throw GradleException("No Go sources: run android/tailnet/sync-go.sh first")
    }
}

val installGo = tasks.register<InstallGo>("installGo") {
    val (name, sha) = goArchives[platformKey] ?: throw GradleException("No pinned Go archive for $platformKey (add it to tailnet/build.gradle.kts)")
    mustRunAfter(checkTailnetHost)
    fileName.set(name); sha256.set(sha); version.set(goVer)
    goRoot.set(goInstallDir)
}

fun GoTask.common() {
    goHome.set(goInstallDir)
    cache.set(cacheDir)
    sourceDir.set(goSrcDir)
    workDir.set(project.layout.buildDirectory.dir("gosrc/$name"))
    sdkDir()?.let { sdk.set(it.absolutePath) }
    ndkDir()?.let { ndk.set(it.absolutePath) }
}

val installGomobile = tasks.register<InstallGomobile>("installGomobile") {
    common()
    dependsOn(installGo)
    goVersion.set(goVer)
    modFiles.from(goSrcDir.file("go.mod"), goSrcDir.file("go.sum"))
    gomobileBin.set(gomobileFile)
}

/** One AAR per build type: debug is for the x86_64 emulator, release for arm64 phones. */
fun registerBind(taskName: String, goTarget: String, aar: Provider<RegularFile>) = tasks.register<BindTailnet>(taskName) {
    common()
    dependsOn(checkTailnetHost, installGomobile)
    goVersion.set(goVer)
    androidApi.set(androidApiLevel)
    target.set(goTarget)
    ndkName.set(provider { ndkDir()?.name ?: "" })
    sources.from(fileTree(goSrcDir) { include("*.go", "go.mod", "go.sum", "build-tags.txt"); exclude("*_test.go") })
    gomobileBin.set(gomobileFile)
    this.aar.set(aar)
}

registerBind("bindTailnetDebug", "android/amd64", layout.buildDirectory.file("outputs/tailnet-debug.aar"))
registerBind("bindTailnetRelease", "android/arm64", layout.buildDirectory.file("outputs/tailnet-release.aar"))
tasks.register("bindTailnet") { dependsOn("bindTailnetDebug", "bindTailnetRelease") }

/** Prints what the build host offers: `gradlew :tailnet:tailnetDoctor`. */
tasks.register("tailnetDoctor") {
    doLast {
        val sdk = sdkDir()
        println("platform=$platformKey sdk=$sdk ndk=${ndkDir()}")
        println("ndks=${sdk?.resolve("ndk")?.list()?.toList()} platforms=${sdk?.resolve("platforms")?.list()?.toList()}")
        println("goSrc=${goSrcDir.asFile} (exists=${goSrcDir.asFile.exists()}) files=${goSrcDir.asFile.list()?.toList()}")
        println("sdkRoot=${sdk?.list()?.toList()} parent=${sdk?.parentFile?.list()?.toList()} cmdline=${sdk?.resolve("cmdline-tools")?.list()?.toList()} buildTools=${sdk?.resolve("build-tools")?.list()?.toList()}")
        println("ndk-bundle=${sdk?.resolve("ndk-bundle")?.exists()} env=${System.getenv().filterKeys { it.contains("NDK", true) || it.contains("GO", true) }}")
        println("JAVA_HOME=${System.getenv("JAVA_HOME")} java.home=${System.getProperty("java.home")}")
    }
}
