package app.revanced.patches.all.misc.apkcleanup

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.patch.stringsOption
import java.io.File

private val DENSITIES = listOf("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
private val DRAWABLE_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg", "gif")
private val MIPMAP_EXTENSIONS = setOf("png", "xml")

private fun groupedDensityDirs(resDir: File, prefix: String): Map<String, MutableMap<String, File>> {
    val groups = mutableMapOf<String, MutableMap<String, File>>()
    resDir.listFiles { f -> f.isDirectory && f.name.split("-").first() == prefix }?.forEach { dir ->
        val tokens = dir.name.split("-")
        val density = tokens.last()
        if (density !in DENSITIES) return@forEach
        val groupKey = tokens.dropLast(1).joinToString("-")
        groups.getOrPut(groupKey) { mutableMapOf() }[density] = dir
    }
    return groups
}

val drawableCleanPatch = resourcePatch(
    name = "Remove Duplicate Graphics",
    description = "Keeps images for selected screen densities (e.g. xhdpi, xxhdpi) and removes copies for all other densities.",
    use = false,
) {
    val targetDensities by stringsOption(
        default = null,
        name = "Target densities",
        description = "Density buckets to keep; duplicates are stripped from every other bucket.",
    )

    execute {
        val resDir = get("res", false)
        val apkRoot = resDir.parentFile ?: File(".")
        
        val baselines = (targetDensities ?: emptyList())
            .flatMap { it.replace("[", "").replace("]", "").replace("\"", "").split(",") }
            .map { it.trim().lowercase() }
            .filter { it in DENSITIES }
            .takeIf { it.isNotEmpty() } ?: listOf("xhdpi") 

        // 1. DỌN DẸP DENSITY KHỎI BẢNG TÀI NGUYÊN ARSC
        val resTable = context.apk.resourceTable
        if (resTable != null) {
            resTable.packages.forEach { pkg ->
                pkg.types.forEach { type ->
                    if (type.name.startsWith("drawable") || type.name.startsWith("mipmap")) {
                        val iterator = type.configs.iterator()
                        while (iterator.hasNext()) {
                            val config = iterator.next()
                            // Kiểm tra xem qualifier của config có chứa density rác không
                            val containsDroppedDensity = DENSITIES.any { d -> 
                                d !in baselines && config.qualifier.contains(d)
                            }
                            if (containsDroppedDensity) {
                                iterator.remove()
                            }
                        }
                    }
                }
            }
        }

        // 2. XOÁ FILE HÌNH QUA VFS (BỎ LỆNH file.delete() VẬT LÝ)
        fun dedupeByBaselineDensities(resDir: File, prefix: String, baselines: List<String>, extensions: Set<String>) {
            groupedDensityDirs(resDir, prefix).values.forEach { densityMap ->
                val baselineNames = mutableSetOf<String>()
                baselines.forEach { baseline ->
                    densityMap[baseline]?.walkTopDown()
                        ?.filter { it.isFile && it.extension.lowercase() in extensions }
                        ?.map { it.name }
                        ?.let { baselineNames.addAll(it) }
                }

                if (baselineNames.isEmpty()) return@forEach

                densityMap.forEach { (density, dir) ->
                    if (density in baselines) return@forEach 
                    dir.walkTopDown()
                        .filter { it.isFile && it.extension.lowercase() in extensions && it.name in baselineNames }
                        .forEach { file ->
                            val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")
                            try {
                                delete(relativePath) // Cắt đuôi qua VFS
                            } catch (e: Exception) {}
                        }
                }
            }
        }

        dedupeByBaselineDensities(resDir, "drawable", baselines, DRAWABLE_EXTENSIONS)
        dedupeByBaselineDensities(resDir, "mipmap", baselines, MIPMAP_EXTENSIONS)
    }
}