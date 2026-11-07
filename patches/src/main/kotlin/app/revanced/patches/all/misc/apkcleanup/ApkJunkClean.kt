package app.revanced.patches.all.misc.apkcleanup

import app.revanced.patcher.patch.rawResourcePatch
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.stringOption
import app.revanced.patcher.patch.stringsOption
import java.io.File
import java.util.logging.Logger

private val PROTECTED_PATTERNS = listOf(
    Regex(""".*META-INF/MANIFEST\.MF$"""),
    Regex(""".*META-INF/services/.*"""),
    // FBSIG: Facebook's own signature file (META-INF/FACEBOOK.FBSIG); kept like the other signature files.
    Regex(""".*META-INF/.*\.(RSA|SF|DSA|EC|FBSIG)$"""),
    Regex("""^(root/)?classes\d*\.dex$"""),
    Regex(""".*resources\.arsc$"""),
    Regex(""".*AndroidManifest\.xml$"""),
    // Read at runtime by libraries (Compose version check, kotlin-reflect, javax.activation, ...): never delete.
    Regex("""^META-INF/androidx\.compose\..*\.version$"""),
    Regex("""^META-INF/.*\.kotlin_module$"""),
    Regex("""^META-INF/mailcap.*"""),
    Regex("""^META-INF/mimetypes.*"""),
    Regex("""^META-INF/native/.*"""),
    Regex("""^META-INF/resources/.*"""),
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

private val JUNK_DIRECTORY_PREFIXES = listOf(
    "assets/dexopt/",
    "com/clevertap/",
    "org/jacoco/",
    "org/joda/",
    "services/",
)

private val EXCLUDED_ROOT_CALLS = listOf(
    // === NHÓM GOOGLE PLAY SERVICES ===
    "play-services-auth.properties",
    "play-services-auth-api-phone.properties",
    "play-services-auth-base.properties",
    "play-services-base.properties",
    "play-services-cloud-messaging.properties",
    "play-services-gcm.properties",
    "play-services-tasks.properties",

    // === NHÓM FIREBASE ===
    "firebase-auth.properties",
    "firebase-auth-interop.properties",
    "firebase-common.properties",
    "firebase-components.properties",
    "firebase-core.properties",
    "firebase-database.properties",
    "firebase-datatransport.properties",
    "firebase-inappmessaging.properties",
    "firebase-inappmessaging-display.properties",
    "firebase-messaging.properties",

    // === NHÓM KHÁC ===
    "core-common.properties",
    "META-INF/androidx.compose.ui_ui.version",
    "androidannotations-api.properties",
    "jetty-dir.css",
    "feature-delivery.properties",
    "feature-delivery-base.properties",
)

private val PACKAGE_NAME = listOf(
    // com.facebook.katana was added: Messenger (same code base) crashes without these files, and they are only ~3 KB in total.
    "com.viber.voip", "com.facebook.orca", "com.whatsapp", "com.zing.zalo", "com.facebook.katana"
)

private val EXACT_ROOT_JUNK = listOf(
    // === NHÓM GOOGLE PLAY SERVICES ===
    "play-services-ads.properties",
    "play-services-ads-base.properties",
    "play-services-ads-identifier.properties",
    "play-services-ads-lite.properties",
    "play-services-analytics.properties",
    "play-services-analytics-impl.properties",
    "play-services-appset.properties",
    "play-services-auth.properties",
    "play-services-auth-api-phone.properties",
    "play-services-auth-base.properties",
    "play-services-base.properties",
    "play-services-basement.properties",
    "play-services-cast.properties",
    "play-services-cast-framework.properties",
    "play-services-clearcut.properties",
    "play-services-cloud-messaging.properties",
    "play-services-drive.properties",
    "play-services-fido.properties",
    "play-services-fitness.properties",
    "play-services-games.properties",
    "play-services-gcm.properties",
    "play-services-identity.properties",
    "play-services-location.properties",
    "play-services-maps.properties",
    "play-services-measurement.properties",
    "play-services-measurement-api.properties",
    "play-services-measurement-base.properties",
    "play-services-measurement-impl.properties",
    "play-services-measurement-sdk.properties",
    "play-services-measurement-sdk-api.properties",
    "play-services-oss-licenses.properties",
    "play-services-pay.properties",
    "play-services-places-placereport.properties",
    "play-services-safetynet.properties",
    "play-services-stats.properties",
    "play-services-tasks.properties",
    "play-services-vision.properties",
    "play-services-vision-common.properties",
    "play-services-wallet.properties",
    "play-services-wearable.properties",

    // === NHÓM FIREBASE ===
    "firebase/perf/v1/perf_metric.proto",
    "firebase-ads.properties",
    "firebase-analytics.properties",
    "firebase-annotations.properties",
    "firebase-auth.properties",
    "firebase-auth-interop.properties",
    "firebase-common.properties",
    "firebase-components.properties",
    "firebase-config.properties",
    "firebase-core.properties",
    "firebase-crashlytics.properties",
    "firebase-database.properties",
    "firebase-datatransport.properties",
    "firebase-dynamic-links.properties",
    "firebase-encoders.properties",
    "firebase-encoders-proto.properties",
    "firebase-firestore.properties",
    "firebase-iid.properties",
    "firebase-iid-interop.properties",
    "firebase-inappmessaging.properties",
    "firebase-inappmessaging-display.properties",
    "firebase-installations.properties",
    "firebase-installations-interop.properties",
    "firebase-measurement-connector.properties",
    "firebase-messaging.properties",
    "firebase-perf.properties",
    "firebase-storage.properties",

    // === NHÓM TRANSPORT ===
    "transport-api.properties",
    "transport-backend-cct.properties",
    "transport-runtime.properties",

    // === BỔ SUNG CÁC MỤC BỊ SÓT SO VỚI REGEX LIST ===
    "ion-java.properties",
    "feature-delivery.properties",
    "feature-delivery-base.properties",
    "facebook_trackers.xml",
    "google_trackers.xml",
    "firebase_trackers.xml",

    // === NHÓM RÁC LẺ & PROTO ===
    "client_analytics.proto",
    "messaging_event.proto",
    "messaging_event_extension.proto",
    "app-update.properties", 
    "billing.properties", 
    "billing-ktx.properties", 
    "review.properties", 
    "hsdp.properties", 
    "core-common.properties", 
    "user-messaging-platform.properties", 
    "ads-mobile-sdk.properties", 
    "DebugProbesKt.bin", 
    "androidsupportmultidexversion.txt", 
    "stamp-cert-sha256", 
    "version-control-info.textproto", 
    "kotlin-tooling-metadata.json",
    "LICENSES", 
    "THIRD-PARTY-NOTICES.txt", 
    "licenses.md", 
    "debug.keystore", 
    "version.properties", 
    "integrity.properties", 
    "androidannotations-api.properties", 
    "jetty-dir.css",
    "tv-ads.properties"
)

// ---------------------------------------------------------------------------------------------------------------------
// Safety design (see README-PATCH.md):
//  * SAFE rules (always): licenses/notices, .proto, tracker lists, version marker files (*.properties, *.version) of ad/analytics/
//    Play Services libraries, org/jacoco/, META-INF/maven and META-INF/proguard.
//  * Files in EXCLUDED_ROOT_CALLS (known to crash some apps) are kept; they are removed only with the option "Aggressive",
//    and never for the apps in PACKAGE_NAME.
//  * AGGRESSIVE rules (option "Aggressive"): the files above plus kotlin/, org/joda/, com/clevertap/, services/,
//    assets/audience_network* and every other META-INF entry. Some apps read these at runtime and crash without them.
//  * Never touched by the junk rules: res/, assets/, lib/ and every file the resource table (resources.arsc) points to.
// ---------------------------------------------------------------------------------------------------------------------

/** Library files that made some apps crash when missing: kept unless "Aggressive" is on (and always kept for PACKAGE_NAME apps). */
private val KNOWN_SENSITIVE_FILES = EXCLUDED_ROOT_CALLS.toSet()

private val EXACT_ROOT_JUNK_NAMES = EXACT_ROOT_JUNK.toSet()

private val SAFE_DIRECTORIES = listOf("org/jacoco/")

/** Folders removed only in aggressive mode (assets/dexopt/ is a separate option). */
private val AGGRESSIVE_DIRECTORIES = JUNK_DIRECTORY_PREFIXES.filter { it != "assets/dexopt/" && it !in SAFE_DIRECTORIES }

private fun globToRegex(glob: String): Regex {
    val sb = StringBuilder()
    for (c in glob.trim()) {
        when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            else -> {
                if ("\\.[]{}()+-^$|".indexOf(c) >= 0) sb.append('\\')
                sb.append(c)
            }
        }
    }
    return Regex(sb.toString())
}

private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

private fun i32(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

/** Reads a binary string pool chunk (AXML or ARSC) that starts at [p]. */
private fun readStringPool(b: ByteArray, p: Int): List<String> {
    val count = i32(b, p + 8)
    val flags = i32(b, p + 16)
    val stringsStart = i32(b, p + 20)
    val headerSize = u16(b, p + 2)
    val utf8 = (flags and 0x100) != 0
    val out = ArrayList<String>(count)

    for (i in 0 until count) {
        var o = p + stringsStart + i32(b, p + headerSize + 4 * i)

        if (utf8) {
            val l1 = b[o++].toInt() and 0xFF
            if ((l1 and 0x80) != 0) o++
            var len = b[o++].toInt() and 0xFF
            if ((len and 0x80) != 0) len = ((len and 0x7F) shl 8) or (b[o++].toInt() and 0xFF)
            out.add(String(b, o, len, Charsets.UTF_8))
        } else {
            var len = u16(b, o)
            o += 2
            if ((len and 0x8000) != 0) {
                len = ((len and 0x7FFF) shl 16) or u16(b, o)
                o += 2
            }
            out.add(String(b, o, len * 2, Charsets.UTF_16LE))
        }
    }

    return out
}

/** Package name from AndroidManifest.xml: binary XML (normal for a raw resource patch) or text XML. */
private fun readPackageName(manifest: File): String? {
    if (!manifest.isFile) return null

    val b = manifest.readBytes()
    if (b.size < 16) return null

    if (u16(b, 0) == 0x0003) {
        var pool: List<String>? = null
        var p = u16(b, 2)

        while (p + 8 <= b.size) {
            val type = u16(b, p)
            val size = i32(b, p + 4)
            if (size <= 0) break

            if (type == 0x0001 && pool == null) pool = readStringPool(b, p)

            if (type == 0x0102 && pool != null) {
                val nameIndex = i32(b, p + 20)
                if (nameIndex < 0 || nameIndex >= pool.size || pool[nameIndex] != "manifest") return null

                val attrStart = u16(b, p + 24)
                val attrSize = u16(b, p + 26)
                val attrCount = u16(b, p + 28)

                for (i in 0 until attrCount) {
                    val ao = p + 16 + attrStart + i * attrSize
                    val ns = i32(b, ao)
                    val an = i32(b, ao + 4)

                    if (ns == -1 && an >= 0 && an < pool.size && pool[an] == "package") {
                        val raw = i32(b, ao + 8)
                        if (raw >= 0 && raw < pool.size) return pool[raw]
                    }
                }

                return null
            }

            p += size
        }

        return null
    }

    // Text XML fallback: only look inside the <manifest ...> start tag.
    val text = String(b, Charsets.UTF_8)
    val start = text.indexOf("<manifest")
    if (start < 0) return null
    val end = text.indexOf('>', start).let { if (it < 0) text.length else it }

    return Regex("""\spackage\s*=\s*["']([^"']+)["']""").find(text.substring(start, end))?.groupValues?.get(1)
}

/**
 * Every string of the global string pool of resources.arsc that looks like a path. This is a superset of the files the
 * resource table points to (works for obfuscated paths such as "r/abc.json" too). Null if it cannot be read.
 */
private fun readReferencedPaths(arsc: File): Set<String>? {
    if (!arsc.isFile) return null

    return try {
        val b = arsc.readBytes()
        if (b.size < 12 || u16(b, 0) != 0x0002) return null

        var p = u16(b, 2)

        while (p + 8 <= b.size) {
            val type = u16(b, p)
            val size = i32(b, p + 4)
            if (size <= 0) return null

            if (type == 0x0001) return readStringPool(b, p).filter { it.indexOf('/') > 0 }.toSet()

            p += size
        }

        null
    } catch (_: Exception) {
        null
    }
}

val apkCleanupPatch = rawResourcePatch(
    name = "APK Junk Cleanup",
    description = "Removes junk and useless files with no runtime purpose inside apk. " +
        "Safe by default; enable \"Aggressive\" for the riskier rules.",
    use = false,
) {
    val splitByArch by booleanOption(
        default = false,
        name = "Keep one",
        description = "Keep native libraries (.so files) for only one CPU architecture. To generate separate APKs for each architecture, run this patch multiple times with a different architecture selected each time.",
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

    val aggressive by booleanOption(
        default = false,
        name = "Aggressive",
        description = "Also remove the library files known to crash some apps (play-services-auth/base/tasks, firebase-common/messaging, " +
            "core-common, ...), kotlin/, org/joda/, com/clevertap/, services/, assets/audience_network* and every other META-INF entry. " +
            "Some apps read these at runtime and crash or misbehave without them.",
    )

    val dropBaseline by booleanOption(
        default = false,
        name = "Remove baseline profile",
        description = "Also delete assets/dexopt/ (baseline profile). Startup can get slower.",
    )

    val protectPaths by stringsOption(
        default = emptyList<String>(),
        name = "Protect paths",
        description = "Paths or globs that must never be deleted (* = any characters, ? = one character), " +
            "for example play-services-base.properties, *.kotlin_module.",
    )

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val manifestFile = get("AndroidManifest.xml")
        val apkRoot = manifestFile.parentFile ?: File(".")
        val isAggressive = aggressive == true

        val detectedPackage = try {
            readPackageName(manifestFile)
        } catch (e: Exception) {
            logger.warning("APK Cleanup: failed to read the package name from the manifest - ${e.message}")
            null
        }
        val isExcludedApp = detectedPackage != null && detectedPackage in PACKAGE_NAME

        val referenced = readReferencedPaths(File(apkRoot, "resources.arsc"))
        if (referenced == null) {
            logger.warning("APK Cleanup: could not read resources.arsc - files inside sub-folders are protected from the junk rules")
        }

        val userProtect = (protectPaths ?: emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.map { globToRegex(it) }

        logger.info(
            "APK Cleanup: package=$detectedPackage excludedApp=$isExcludedApp mode=${if (isAggressive) "aggressive" else "safe"} " +
                "referencedPaths=${referenced?.size ?: "unknown"}",
        )

        fun isProtected(relativePath: String) =
            PROTECTED_PATTERNS.any { it.matches(relativePath) } || userProtect.any { it.matches(relativePath) }

        // res/, assets/, lib/ and anything the resource table points to is never "junk".
        fun isShielded(relativePath: String): Boolean {
            if (relativePath.startsWith("res/") || relativePath.startsWith("assets/") || relativePath.startsWith("lib/")) return true
            if (referenced != null) return relativePath in referenced

            return relativePath.contains('/') && !relativePath.startsWith("META-INF/")
        }

        fun junkReason(relativePath: String): String? {
            if (isProtected(relativePath) || isShielded(relativePath)) return null

            // Files that made some apps crash: kept, except in aggressive mode for apps that are not on the exclusion list.
            if (relativePath in KNOWN_SENSITIVE_FILES) {
                return if (isAggressive && !isExcludedApp) "known-sensitive library file (aggressive)" else null
            }

            if (JUNK_PATTERNS.any { it.matches(relativePath) }) return "junk pattern"
            if (relativePath in EXACT_ROOT_JUNK_NAMES) return "junk file name"
            if (SAFE_DIRECTORIES.any { relativePath.startsWith(it) }) return "junk folder"
            if (relativePath.startsWith("META-INF/maven/") || relativePath.startsWith("META-INF/proguard/")) return "META-INF build leftovers"

            if (!isAggressive) return null

            if (relativePath.startsWith("kotlin/")) return "kotlin/ folder"
            if (AGGRESSIVE_DIRECTORIES.any { relativePath.startsWith(it) }) return "junk folder"
            if (relativePath.startsWith("META-INF/")) return "other META-INF"

            return null
        }

        var removedFiles = 0
        var freedBytes = 0L

        fun removeFile(relativePath: String, file: File, why: String) {
            val size = file.length()

            try {
                delete(relativePath)
                removedFiles++
                freedBytes += size
                logger.info("Removed ($why): $relativePath (${size}B)")
            } catch (e: Exception) {
                logger.warning("APK Cleanup: failed to remove $relativePath - ${e.message}")
            }
        }

        val files = apkRoot.walkTopDown().filter { it.isFile }.toList()

        for (file in files) {
            val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")

            val why = when {
                userProtect.any { it.matches(relativePath) } -> null
                // audience_network is an assets/ folder, so it is handled before the shield (aggressive only).
                isAggressive && !isProtected(relativePath) &&
                    (relativePath == "assets/audience_network.dex" || relativePath.startsWith("assets/audience_network/")) -> "audience_network"
                dropBaseline == true && !isProtected(relativePath) && relativePath.startsWith("assets/dexopt/") -> "baseline profile"
                else -> junkReason(relativePath)
            }

            if (why != null) removeFile(relativePath, file, why)
        }

        apkRoot.walkBottomUp()
            .filter { it.isDirectory && it != apkRoot && it.listFiles()?.isEmpty() == true }
            .forEach { it.delete() }

        if (splitByArch == true) {
            val archToKeep = targetArch ?: "arm64-v8a"
            val libDir = get("lib")

            if (libDir.isDirectory) {
                val archNames = libDir.list()?.toList() ?: emptyList()
                val hasTarget = archNames.contains(archToKeep)

                if (hasTarget) {
                    for (arch in archNames.filter { it != archToKeep }) {
                        val archDir = File(libDir, arch)

                        for (file in archDir.walkTopDown().filter { it.isFile }.toList()) {
                            val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")
                            if (!isProtected(relativePath)) removeFile(relativePath, file, "other CPU lib")
                        }

                        archDir.walkBottomUp().filter { it.isDirectory && it.listFiles()?.isEmpty() == true }.forEach { it.delete() }
                    }
                } else {
                    logger.warning(
                        "APK Cleanup: selected architecture \"$archToKeep\" not found in lib/. " +
                            "Available: ${archNames.joinToString()}. Keeping all architectures.",
                    )
                }
            }
        }

        logger.info("APK Cleanup: removed $removedFiles files, freed ${freedBytes / 1024}KB")
    }
}
