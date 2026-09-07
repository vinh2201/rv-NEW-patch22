package app.revanced.patches.all.misc.apkcleanup

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.patch.stringsOption
import java.io.File
import java.util.logging.Logger

private val logger = Logger.getLogger("LangCleanPatch")

private val KNOWN_NON_LANGUAGE_SEGMENTS = setOf(
    "car",      
    "any",      
)

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
                val lang = parts[1].lowercase()
                val region = parts.getOrNull(2)
                    ?.takeIf { it.length == 2 && it.all { c -> c.isUpperCase() } }
                    ?.lowercase()
                result.add(LangQualifier(lang, region))
            }
            i++
            continue
        }

        if (seg.length in 2..3 && seg.all { it.isLowerCase() } && seg !in KNOWN_NON_LANGUAGE_SEGMENTS) {
            val next = rest.getOrNull(i + 1)
            val isRegion = next != null && next.startsWith("r") && next.length == 3 &&
                next.drop(1).all { it.isUpperCase() }
            val region = if (isRegion) next!!.drop(1).lowercase() else null
            result.add(LangQualifier(seg, region))
            i += if (isRegion) 2 else 1
            continue
        }

        i++
    }

    return result
}

val langCleanPatch = resourcePatch(
    name = "Remove Languages",
    description = "Removes translations for languages you don't use. Only keeps the languages you pick. ",
    use = false,
) {
    val keepLanguages by stringsOption(
        default = listOf("en", "vi"),
        name = "Keep languages",
        description = "Exact resource variants to preserve. \"ru\" keeps ONLY the unqualified ru dir " +
            "(values-ru); it does NOT pull in ru-rRU or any other region. \"en-rIN\" keeps ONLY that " +
            "region. List every variant you want kept, e.g. en, en-rIN, ru — anything not listed is removed.",
    )

    execute {
        val resDir = get("res")
        val apkRoot = resDir.parentFile ?: File(".")

        // 1. XOÁ CẤU HÌNH NGÔN NGỮ KHỎI BẢNG TÀI NGUYÊN (ARSC) TRÊN RAM
        val resTable = context.apk.resourceTable
        if (resTable != null) {
            resTable.packages.forEach { pkg ->
                pkg.types.forEach { type ->
                    val iterator = type.configs.iterator()
                    while (iterator.hasNext()) {
                        val config = iterator.next()
                        val configQualifiers = extractLanguageQualifiers(config.qualifier)
                        
                        if (configQualifiers.isNotEmpty()) {
                            val shouldKeep = configQualifiers.any { q -> (q.lang to q.region) in keepSet }
                            if (!shouldKeep) {
                                iterator.remove() // Gỡ hẳn ánh xạ ID, ngăn app gọi nhầm và crash
                            }
                        }
                    }
                }
            }
        }

        // 2. LOẠI BỎ FILE QUA VFS (KHÔNG DÙNG deleteRecursively VẬT LÝ NỮA)
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
                dir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")
                    try {
                        delete(relativePath) // Gạch tên khỏi VFS Repacker
                    } catch (e: Exception) {}
                }
                removedDirs++
            }
        }

        logger.info("Language cleanup: kept $keptDirs dirs, removed $removedDirs dirs and cleared ARSC entries.")
    }
}