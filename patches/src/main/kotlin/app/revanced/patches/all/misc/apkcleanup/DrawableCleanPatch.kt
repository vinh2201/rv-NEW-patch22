package app.revanced.patches.all.misc.apkcleanup

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.patch.stringsOption
import java.io.File
import java.util.logging.Logger

private val DENSITIES = mapOf("ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640)
private val SPECIAL_DENSITIES = setOf("nodpi", "anydpi")
private val IMAGE_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg", "gif")
private val RESOURCE_TYPES = setOf("drawable", "mipmap")

// Old aapt added "-v4" to every density folder. It means the same as no version.
private val OLD_VERSION_QUALIFIER = Regex("v[1-4]")
private val NUMBER_DPI = Regex("(\\d+)dpi")

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

private fun densityOf(token: String): Int? =
    DENSITIES[token] ?: NUMBER_DPI.matchEntire(token)?.groupValues?.get(1)?.toIntOrNull()

/** The density Android picks for a screen of density [target] among [available]: exact, else the smallest above, else the largest below. */
private fun bestDensity(target: Int, available: Collection<Int>): Int {
    if (target in available) return target

    return available.filter { it > target }.minOrNull() ?: available.filter { it < target }.max()
}

/** One drawable/mipmap folder. [density] is 160 for a folder without density (Android treats it as mdpi). */
private class DensityDir(val dir: File, val density: Int, val special: Boolean)

val drawableCleanPatch = resourcePatch(
    name = "Remove Duplicate Graphics",
    description = "Keeps images for the selected screen densities (e.g. xhdpi, xxhdpi) and removes the copies for the other densities.",
    use = false,
) {
    val targetDensities by stringsOption(
        default = null,
        name = "Target densities",
        description = "Densities to keep, e.g. xxhdpi or xhdpi,xxhdpi (ldpi, mdpi, tvdpi, hdpi, xhdpi, xxhdpi, xxxhdpi or a number like 420). " +
            "For every image the copy Android would pick for those densities is kept, the other densities of that image are removed. " +
            "Default: xhdpi.",
    )

    apply {
        val logger = Logger.getLogger(this::class.java.name)

        val resDirRaw = get("res", false)
        val apkRoot = getApkRoot(resDirRaw)
        val resDir = File(apkRoot, "res")

        if (!resDir.exists() || !resDir.isDirectory) return@apply

        val targets = (targetDensities ?: emptyList())
            .flatMap { it.replace("[", "").replace("]", "").replace("\"", "").split(",") }
            .map { it.trim().lowercase() }
            .mapNotNull { densityOf(it) }
            .distinct()
            .ifEmpty { listOf(DENSITIES.getValue("xhdpi")) }

        // Folders that differ only by density belong to the same group, e.g. drawable-night-hdpi and drawable-night-xxhdpi.
        val groups = mutableMapOf<String, MutableList<DensityDir>>()

        resDir.listFiles { file -> file.isDirectory }?.forEach { dir ->
            val tokens = dir.name.split("-")
            if (tokens.first() !in RESOURCE_TYPES) return@forEach

            var density: Int? = null
            var special = false
            val others = mutableListOf<String>()

            for (token in tokens.drop(1)) {
                val tokenDensity = densityOf(token)

                when {
                    tokenDensity != null && density == null -> density = tokenDensity
                    token in SPECIAL_DENSITIES -> special = true
                    OLD_VERSION_QUALIFIER.matches(token) -> Unit
                    else -> others += token
                }
            }

            groups.getOrPut(tokens.first() + "|" + others.joinToString("-")) { mutableListOf() } +=
                DensityDir(dir, density ?: 160, special)
        }

        var removedFiles = 0
        var removedBytes = 0L

        groups.values.filter { it.size >= 2 }.forEach { dirs ->
            // Resource name -> its files, one per density folder.
            val byName = mutableMapOf<String, MutableList<Pair<DensityDir, File>>>()

            dirs.forEach { densityDir ->
                densityDir.dir.listFiles { file -> file.isFile }?.forEach { file ->
                    // "icon.9.png" is the resource "icon".
                    byName.getOrPut(file.name.substringBefore('.')) { mutableListOf() } += densityDir to file
                }
            }

            byName.values.forEach variants@{ variants ->
                if (variants.size < 2) return@variants

                // Leave alone anything that is not a plain set of bitmaps: xml drawables, nodpi/anydpi variants,
                // or two copies for the same density (we cannot know which one Android prefers).
                if (variants.any { (densityDir, file) -> densityDir.special || file.extension.lowercase() !in IMAGE_EXTENSIONS }) return@variants

                val densities = variants.map { it.first.density }
                if (densities.toSet().size != densities.size) return@variants

                val keep = targets.map { bestDensity(it, densities) }.toSet()

                variants.filter { it.first.density !in keep }.forEach { (_, file) ->
                    val relativePath = file.relativeTo(apkRoot).path.replace("\\", "/")
                    val size = file.length()

                    delete(relativePath)

                    if (file.delete()) {
                        removedFiles++
                        removedBytes += size
                    }
                }
            }
        }

        resDir.walkBottomUp()
            .filter { it.isDirectory && it != resDir && it.listFiles()?.isEmpty() == true }
            .forEach { it.delete() }

        logger.info("Removed $removedFiles duplicate graphics (${removedBytes / 1024} KB), keeping the copy for ${targets.joinToString { "${it}dpi" }}")
    }
}
