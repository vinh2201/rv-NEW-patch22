package app.revanced.patches.all.misc.apkcleanup

import app.revanced.patcher.patch.rawResourcePatch
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.stringOption
import java.io.File
import java.util.logging.Logger

private val logger = Logger.getLogger("ApkCleanupPatch")

private val PROTECTED_PATTERNS = listOf(
    Regex(""".*META-INF/MANIFEST\.MF$"""),
    Regex(""".*META-INF/services/.*"""),
    Regex(""".*META-INF/.*\.(RSA|SF|DSA|EC)$"""),
    Regex("""^(root/)?classes\d*\.dex$"""),
    Regex(""".*resources\.arsc$"""),
    Regex(""".*AndroidManifest\.xml$"""),
)

private val JUNK_PATTERNS = listOf(
    Regex(""".*play-services-.*\.properties$"""),
    Regex(""".*firebase-.*\.properties$"""),
    Regex(""".*app-update\.properties$"""),
    Regex(""".*billing\.properties$"""),
    Regex(""".*review\.properties$"""),
    Regex(""".*\.proto$"""),
    Regex(""".*DebugProbesKt\.bin$"""),
    Regex(""".*\.version$"""),
    Regex(""".*_VERSION$"""),
    Regex(""$.*stamp-cert-sha256$"""),
    Regex(""".*kotlin-tooling-metadata\.json$"""),
    Regex(""".*META-INF/CHANGES$"""),
    Regex(""".*META-INF/README\.md$"""),
    Regex(""".*META-INF/NOTICE.*"""),
    Regex(""".*META-INF/LICENSE.*"""),
    Regex(""$.*(?:^|/)LICENSES$"""),
)

private val EXCLUDED_PREFIXES = listOf("assets/", "res/")

val apkCleanupPatch = rawResourcePatch(
    name = "APK Junk Cleanup",
    description = "Removes junk and useless files safely without breaking app runtime.",
    use = false,
) {
    val splitByArch by booleanOption(
        default = false,
        name = "Keep one architecture",
        description = "Keep native libraries (.so files) for only one CPU architecture.",
    )

    val targetArch by stringOption(
        default = "armeabi-v7a",
        values = mapOf(
            "arm64-v8a" to "ARM64 (arm64-v8a)",
            "armeabi-v7a" to "ARMv7 (armeabi-v7a)",
            "x86" to "x86",
            "x86_64" to "x86_64",
        ),
        name = "Target architecture",
        description = "Architecture to keep.",
    )

    execute {
        val manifestFile = get("AndroidManifest.xml")
        val apkRoot = manifestFile.parentFile ?: File(".")

        var removedFiles = 0
        var freedBytes = 0L

        fun isProtected(relativePath: String) = PROTECTED_PATTERNS.any { it.matches(relativePath) }

        apkRoot.walkTopDown()
            .filter { it.isFile }
            .toList()
            .forEach { file ->
                val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")

                if (isProtected(relativePath)) return@forEach
                if (EXCLUDED_PREFIXES.any { relativePath.startsWith(it) }) return@forEach

                if (JUNK_PATTERNS.any { it.matches(relativePath) }) {
                    val size = file.length()
                    try {
                        delete(relativePath)
                        removedFiles++
                        freedBytes += size
                    } catch (e: Exception) {
                        // Bỏ qua nếu Patcher không cho phép xóa
                    }
                }
            }

        // Xử lý kiến trúc lib an toàn hơn
        if (splitByArch == true) {
            val archToKeep = targetArch ?: "armeabi-v7a"
            val libDir = get("lib")

            if (libDir.isDirectory) {
                libDir.listFiles()?.forEach { archDir ->
                    if (archDir.isDirectory && archDir.name != archToKeep) {
                        archDir.walkTopDown().filter { it.isFile }.forEach { f ->
                            val rel = f.relativeTo(apkRoot).path.replace("\\", "/")
                            try {
                                delete(rel)
                            } catch (e: Exception) {}
                        }
                    }
                }
            }
        }

        logger.info("APK Cleanup: removed $removedFiles files, freed ${freedBytes / 1024}KB")
    }
}
