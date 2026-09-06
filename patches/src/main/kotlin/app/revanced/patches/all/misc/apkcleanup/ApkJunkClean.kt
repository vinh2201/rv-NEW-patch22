package app.revanced.patches.all.misc.apkcleanup

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.patch.stringOption
import java.io.File
import java.util.logging.Logger

@Suppress("unused")
val apkJunkCleanupPatch = resourcePatch(
    name = "Apk Junk Cleanup",
    description = "Removes unused CPU libraries to shrink the APK. Keep only your device's architecture.",
    use = false,
) {
    val keepArch by stringOption(
        default = "armeabi-v7a",
        name = "Keep one",
        description = "Keep native libraries (.so files) for only one CPU architecture. To generate separate APKs for each architecture, run this patch multiple times with a different architecture selected each time.",
        values = linkedMapOf(
            "arm64-v8a (most modern devices)" to "arm64-v8a",
            "armeabi-v7a (32-bit ARM)" to "armeabi-v7a",
            "x86 (32-bit Intel)" to "x86",
            "x86_64 (64-bit Intel)" to "x86_64",
        ),
    )

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val selected = keepArch?.trim().takeIf { !it.isNullOrEmpty() } ?: "arm64-v8a"

        // SỬ DỤNG apk.directory ĐỂ TRỎ THẲNG VỀ THƯ MỤC GỐC, KHÔNG DÙNG get() NỮA
        val libDir = File(apk.directory, "lib")
        
        // Thêm đường dẫn tuyệt đối vào log để lỡ nó lỗi bác còn soi được nó đang chui vào đâu =))
        if (!libDir.isDirectory) {
            logger.warning("No lib/ directory found at: ${libDir.absolutePath}. No changes applied.")
            return@execute
        }

        val archDirs = libDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        if (archDirs.isEmpty()) {
            logger.warning("lib/ is empty. No changes applied.")
            return@execute
        }

        var removed = 0
        var kept = 0
        for (dir in archDirs) {
            if (dir.name == selected) {
                kept++
                continue
            }
            if (dir.deleteRecursively()) {
                logger.info("Removed lib/${dir.name}/")
                removed++
            } else {
                logger.warning("Could not delete lib/${dir.name}/")
            }
        }

        if (kept == 0) {
            logger.warning("Selected architecture $selected not found in lib/. No changes applied.")
        } else if (removed > 0) {
            logger.info("Kept $selected, removed $removed ABI(s)")
        } else {
            logger.info("Only $selected present. No changes applied.")
        }
    }
}
