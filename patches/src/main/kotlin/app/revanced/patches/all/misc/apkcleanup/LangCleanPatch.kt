package app.revanced.patches.all.misc.apkcleanup

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.patch.stringsOption
import java.io.File
import java.util.logging.Logger

private val KNOWN_NON_LANGUAGE_SEGMENTS = setOf(
    "car",
    "any",
)

/** Android still names some resource folders with the old ISO codes: Indonesian is "in", Hebrew "iw", Yiddish "ji". */
private fun canonicalLanguage(lang: String): String = when (lang) {
    "id" -> "in"
    "he" -> "iw"
    "yi" -> "ji"
    else -> lang
}

/** "rUS" -> "us", "r419" -> "419", anything else -> null. */
private fun regionOfToken(token: String): String? =
    token.takeIf { it.length == 3 && it[0] == 'r' && it.drop(1).all { c -> c.isUpperCase() } }?.drop(1)?.lowercase()
        ?: token.takeIf { it.length == 4 && it[0] == 'r' && it.drop(1).all { c -> c.isDigit() } }?.drop(1)

private data class LangQualifier(val lang: String, val region: String?)

private fun extractLanguageQualifiers(dirName: String): List<LangQualifier> {
    val segments = dirName.split("-")
    if (segments.size < 2) return emptyList()

    val rest = segments.drop(1)
    val result = mutableListOf<LangQualifier>()
    var i = 0

    while (i < rest.size) {
        val seg = rest[i]

        if (seg.startsWith("b+")) {
            val parts = seg.split("+")
            if (parts.size >= 2) {
                val lang = canonicalLanguage(parts[1].lowercase())
                val region = parts.getOrNull(2)
                    ?.takeIf { it.length == 2 && it.all { c -> c.isUpperCase() } }
                    ?.lowercase()
                result.add(LangQualifier(lang, region))
            }
            i++
            continue
        }

        if (seg.length in 2..3 && seg.all { it.isLowerCase() } && seg !in KNOWN_NON_LANGUAGE_SEGMENTS) {
            val region = rest.getOrNull(i + 1)?.let { regionOfToken(it) }
            result.add(LangQualifier(canonicalLanguage(seg), region))
            i += if (region != null) 2 else 1
            continue
        }

        i++
    }

    return result
}

/** One entry of the "Keep languages" option: "ru", "en-rIN", "en-IN", "en_IN", "pt-r419" or "b+sr+Latn". */
private fun parseKeepEntry(raw: String): Pair<String, String?>? {
    val text = raw.trim().trim('"', '\'')
    if (text.isEmpty()) return null

    if (text.startsWith("b+")) {
        val parts = text.split("+")
        val lang = parts.getOrNull(1)?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val region = parts.getOrNull(2)?.takeIf { it.length == 2 && it.all { c -> c.isUpperCase() } }?.lowercase()
        return canonicalLanguage(lang) to region
    }

    val parts = text.split("-", "_")
    val lang = parts[0].lowercase().takeIf { it.isNotEmpty() } ?: return null
    val region = parts.getOrNull(1)?.let { part ->
        // "rIN" -> "IN", "IN" -> "IN", "r419" -> "419"
        val body = if (part.length > 2 && part[0] == 'r') part.drop(1) else part

        when {
            body.length == 2 && body.all { it.isLetter() } -> body.lowercase()
            body.length == 3 && body.all { it.isDigit() } -> body
            else -> null
        }
    }

    return canonicalLanguage(lang) to region
}

private fun getApkRoot(startFile: File): File {
    var current: File? = startFile
    while (current != null) {
        if (File(current, "resources.arsc").exists() && File(current, "AndroidManifest.xml").exists()) {
            return current
        }
        current = current.parentFile
    }
    return startFile.parentFile ?: File(".")
}

val langCleanPatch = resourcePatch(
    name = "Remove Languages",
    description = "Removes translations for languages you don't use. Only keeps the languages you pick.",
    use = false,
) {
    val keepLanguages by stringsOption(
        default = listOf("en", "vi"),
        name = "Keep languages",
        description = "Exact resource variants to preserve. \"ru\" keeps ONLY the unqualified ru dir " +
            "(values-ru); it does NOT pull in ru-rRU or any other region. \"en-rIN\" (or en-IN) keeps ONLY that " +
            "region. List every variant you want kept, e.g. en, en-rIN, ru — anything not listed is removed. " +
            "\"id\", \"he\" and \"yi\" also match Android's old folder names in, iw and ji.",
    )

    apply {
        val logger = Logger.getLogger(this::class.java.name)
        val resDirRaw = get("res", false)
        val apkRoot = getApkRoot(resDirRaw)
        val resDir = File(apkRoot, "res")

        if (!resDir.exists() || !resDir.isDirectory) {
            logger.warning("Language cleanup: res/ directory not found")
            return@apply
        }

        val keepSet: Set<Pair<String, String?>> = (keepLanguages ?: emptyList())
            .flatMap { it.replace("[", "").replace("]", "").split(",") }
            .mapNotNull { parseKeepEntry(it) }
            .toSet()

        if (keepSet.isEmpty()) {
            // Keeping nothing would remove every translation, which is never what someone wants. Do nothing instead.
            logger.warning("Language cleanup: no language to keep was given, nothing removed")
            return@apply
        }

        var removedDirs = 0
        var keptDirs = 0

        resDir.listFiles { file -> file.isDirectory }?.forEach { dir ->
            val qualifiers = extractLanguageQualifiers(dir.name)

            if (qualifiers.isEmpty()) {
                keptDirs++
                return@forEach
            }

            val shouldKeep = qualifiers.any { q -> (q.lang to q.region) in keepSet }

            if (shouldKeep) {
                keptDirs++
            } else {
                val filesToDelete = dir.walkTopDown().filter { it.isFile }.toList()
                val size = filesToDelete.sumOf { it.length() }

                filesToDelete.forEach { file ->
                    val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")
                    try {
                        delete(relativePath)
                    } catch (_: Exception) {
                    }
                }

                dir.deleteRecursively()
                removedDirs++

                val label = qualifiers.joinToString { q ->
                    if (q.region != null) "${q.lang}-${q.region}" else q.lang
                }
                logger.info("Removed ${dir.name} (${size / 1024}KB) — languages: $label")
            }
        }

        logger.info("Language cleanup: kept $keptDirs dirs, removed $removedDirs dirs")
    }
}
