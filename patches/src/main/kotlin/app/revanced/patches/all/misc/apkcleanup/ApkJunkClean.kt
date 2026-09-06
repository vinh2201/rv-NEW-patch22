package app.revanced.patches.all.misc.apkcleanup

import app.revanced.patcher.patch.rawResourcePatch
import app.revanced.patcher.patch.stringOption
import java.util.logging.Logger

@Suppress("unused")
val apkJunkCleanupPatch = rawResourcePatch(
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

        val libDir = get("lib")

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
            val archName = dir.name
            if (archName == selected) {
                kept++
                continue
            }
            
            // ĐỌC DANH SÁCH FILE VÀ DÙNG API delete() CỦA PATCHER ĐỂ XOÁ TRIỆT ĐỂ
            val filenames = dir.list()
            if (filenames != null && filenames.isNotEmpty()) {
                filenames.forEach { filename ->
                    // Khai báo cho Patcher biết cần gạch tên file này lúc đóng gói
                    delete("lib/$archName/$filename")
                }
                logger.info("Removed all files in lib/$archName/")
                removed++
            } else {
                logger.warning("Directory lib/$archName/ is empty or cannot be read.")
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
