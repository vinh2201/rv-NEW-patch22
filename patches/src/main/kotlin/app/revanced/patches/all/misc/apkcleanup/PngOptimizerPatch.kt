package app.revanced.patches.all.misc.apkcleanup

import app.revanced.patcher.patch.resourcePatch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

private val logger = Logger.getLogger("PngOptimizerPatch")

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
    0x0D, 0x0A, 0x1A, 0x0A,
)

private val STRIPPABLE_CHUNK_TYPES = setOf("tEXt", "zTXt", "iTXt", "tIME", "pHYs", "hIST", "sPLT")

private class PngChunk(val type: String, val data: ByteArray)
private sealed class OptimizeResult {
    data class Success(val bytes: ByteArray, val saved: Int) : OptimizeResult()
    data class Skipped(val reason: String) : OptimizeResult()
}

private fun readInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
    ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
    (bytes[offset + 3].toInt() and 0xFF)

private fun writeInt(out: ByteArrayOutputStream, value: Int) {
    out.write((value ushr 24) and 0xFF)
    out.write((value ushr 16) and 0xFF)
    out.write((value ushr 8) and 0xFF)
    out.write(value and 0xFF)
}

private fun parseChunks(bytes: ByteArray): List<PngChunk>? {
    if (bytes.size < 8 || !PNG_SIGNATURE.contentEquals(bytes.copyOfRange(0, 8))) return null
    val chunks = mutableListOf<PngChunk>()
    var offset = 8
    while (offset + 12 <= bytes.size) {
        val length = readInt(bytes, offset)
        val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
        val dataStart = offset + 8
        val dataEnd = dataStart + length
        if (length < 0 || dataEnd + 4 > bytes.size) return null
        chunks += PngChunk(type, bytes.copyOfRange(dataStart, dataEnd))
        offset = dataEnd + 4
    }
    return chunks
}

private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
    writeInt(out, data.size)
    val typeAndData = ByteArrayOutputStream(4 + data.size).use { baos ->
        baos.write(type.toByteArray(Charsets.US_ASCII))
        baos.write(data)
        baos.toByteArray()
    }
    out.write(typeAndData)
    val crc = CRC32().apply { update(typeAndData) }.value.toInt()
    writeInt(out, crc)
}

private fun optimizePng(original: ByteArray): OptimizeResult {
    val chunks = parseChunks(original) ?: return OptimizeResult.Skipped("parse failed")
    val idatData = ByteArrayOutputStream().use { out ->
        chunks.filter { it.type == "IDAT" }.forEach { out.write(it.data) }
        out.toByteArray()
    }
    if (idatData.isEmpty()) return OptimizeResult.Skipped("no IDAT")

    val raw = try {
        Inflater().let { inf ->
            inf.setInput(idatData)
            ByteArrayOutputStream().use { out ->
                val buf = ByteArray(8192)
                while (!inf.finished()) {
                    val count = inf.inflate(buf)
                    if (count == 0 && inf.needsInput()) break
                    out.write(buf, 0, count)
                }
                inf.end()
                out.toByteArray()
            }
        }
    } catch (e: Exception) {
        return OptimizeResult.Skipped("inflate failed")
    }

    val recompressed = Deflater(Deflater.BEST_COMPRESSION, false).let { def ->
        def.setInput(raw)
        def.finish()
        ByteArrayOutputStream().use { out ->
            val buf = ByteArray(8192)
            while (!def.finished()) {
                out.write(buf, 0, def.deflate(buf))
            }
            def.end()
            out.toByteArray()
        }
    }

    val out = ByteArrayOutputStream(original.size).use { baos ->
        baos.write(PNG_SIGNATURE)
        var idatWritten = false
        for (chunk in chunks) {
            when {
                chunk.type == "IDAT" -> {
                    if (!idatWritten) {
                        writeChunk(baos, "IDAT", recompressed)
                        idatWritten = true
                    }
                }
                chunk.type in STRIPPABLE_CHUNK_TYPES -> Unit
                else -> writeChunk(baos, chunk.type, chunk.data)
            }
        }
        baos.toByteArray()
    }

    return if (out.size < original.size) OptimizeResult.Success(out, original.size - out.size)
    else OptimizeResult.Skipped("already optimal")
}

val pngOptimizerPatch = resourcePatch(
    name = "Png Optimizer",
    description = "Compresses PNG images strictly inside assets/ to prevent resource compilation crashes.",
    use = false,
) {
    execute {
        // CHỈ quét assets/ để tuyệt đối an toàn với API 22, tránh xa thư mục res/
        val assetsDir = get("assets", false)
        if (!assetsDir.isDirectory) return@execute

        val pngFiles = assetsDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("png", ignoreCase = true) && it.length() <= 5_000_000 }
            .toList()

        val optimizedCount = AtomicInteger(0)
        val freedBytes = AtomicLong(0L)

        pngFiles.parallelStream().forEach { file ->
            val original = file.readBytes()
            val result = try { optimizePng(original) } catch (e: Exception) { null }
            if (result is OptimizeResult.Success) {
                file.writeBytes(result.bytes)
                optimizedCount.incrementAndGet()
                freedBytes.addAndGet(result.saved.toLong())
            }
        }

        logger.info("PNG optimizer (assets-only): optimized=${optimizedCount.get()}, freed=${freedBytes.get() / 1024}KB")
    }
}