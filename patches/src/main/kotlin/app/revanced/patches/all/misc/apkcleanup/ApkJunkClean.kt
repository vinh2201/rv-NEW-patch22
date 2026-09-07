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
    Regex(""".*billing-ktx\.properties$"""),
    Regex(""".*review\.properties$"""),
    Regex(""".*hsdp\.properties$"""),
    Regex(""".*core-common\.properties$"""),
    Regex(""".*user-messaging-platform\.properties$"""),
    Regex(""".*feature-delivery.*\.properties$"""),
    Regex(""".*ads-mobile-sdk\.properties$"""),
    Regex(""".*\.proto$"""),
    Regex(""".*DebugProbesKt\.bin$"""),
    Regex(""".*\.version$"""),
    Regex(""".*_VERSION$"""),
    Regex(""".*androidsupportmultidexversion\.txt$"""),
    Regex(""".*stamp-cert-sha256$"""),
    Regex(""".*version-control-info\.textproto$"""),
    Regex(""".*kotlin-tooling-metadata\.json$"""),
    Regex(""".*META-INF/CHANGES$"""),
    Regex(""".*META-INF/README\.md$"""),
    Regex(""".*META-INF/NOTICE.*"""),
    Regex(""".*META-INF/LICENSE.*"""),
    Regex(""".*(?:^|/)LICENSES$"""),
    Regex(""".*ion-java\.properties$"""),
    Regex(""".*THIRD-PARTY-NOTICES\.txt$"""),
    Regex(""".*licenses\.md$"""),
    Regex(""".*debug\.keystore$"""),
    Regex(""".*_trackers\.xml$"""),
    Regex(""".*version\.properties$"""),
    Regex(""".*integrity\.properties$"""),
    Regex(""".*androidannotations-api\.properties$"""),
    Regex(""".*transport-.*\.properties$"""),
    Regex(""".*jetty-dir\.css$"""),
)

private val EXCLUDED_PREFIXES = listOf("assets/", "res/")

val apkCleanupPatch = rawResourcePatch(
    name = "APK Junk Cleanup",
    description = "Removes junk and useless files with no runtime purpose inside apk.",
    use = false,
) {
    val splitByArch by booleanOption(
        default = false,
        name = "Keep one",
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
        description = "Which architecture to keep when splitting is enabled.",
    )

    execute {
        val manifestFile = get("AndroidManifest.xml")
        val apkRoot = manifestFile.parentFile ?: File(".")

        var removedFiles = 0
        var freedBytes = 0L

        fun isProtected(relativePath: String) = PROTECTED_PATTERNS.any { it.matches(relativePath) }

        fun removeTree(path: String) {
            val entry = get(path)
            if (entry.isDirectory) {
                entry.list()?.forEach { child -> removeTree("$path/$child") }
                entry.delete()
            } else if (entry.isFile) {
                if (isProtected(path)) return
                val size = entry.length()
                try {
                    delete(path)
                    removedFiles++
                    freedBytes += size
                } catch (e: Exception) {
                    logger.warning("APK Cleanup: failed to delete $path. Error: ${e.message}")
                }
            }
        }

        apkRoot.walkTopDown()
            .filter { it.isFile }
            .toList()
            .forEach { file ->
                val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")

                if (isProtected(path = relativePath)) return@forEach
                if (EXCLUDED_PREFIXES.any { relativePath.startsWith(it) }) return@forEach

                if (JUNK_PATTERNS.any { it.matches(relativePath) }) {
                    val size = file.length()
                    try {
                        delete(relativePath)
                        removedFiles++
                        freedBytes += size
                    } catch (e: Exception) {
                        logger.warning("APK Cleanup: failed to remove file $relativePath")
                    }
                }
            }

        listOf("kotlin", "assets/audience_network.dex", "assets/audience_network").forEach { 
            try { removeTree(it) } catch (_: Exception) {}
        }

        try {
            get("META-INF").takeIf { it.isDirectory }?.list()?.forEach { name ->
                if (name.lowercase() != "services") {
                    try { removeTree("META-INF/$name") } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        apkRoot.walkBottomUp()
            .filter { it.isDirectory && it != apkRoot && it.listFiles()?.isEmpty() == true }
            .forEach { it.delete() }

        if (splitByArch == true) {
            val archToKeep = targetArch ?: "arm64-v8a"
            val libDir = get("lib")

            if (libDir.isDirectory) {
                val archNames = libDir.list()?.toList() ?: emptyList()
                if (archNames.contains(archToKeep)) {
                    archNames.filter { it != archToKeep }.forEach { arch ->
                        try { removeTree("lib/$arch") } catch (_: Exception) {}
                    }
                }
            }
        }

        logger.info("APK Cleanup: removed $removedFiles files, freed ${freedBytes / 1024}KB")
    }
}