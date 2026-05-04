package app.revanced.extension.twitter.utils.stream

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

object StreamUtils {
    @Throws(IOException::class)
    fun toString(inputStream: InputStream): String {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        try {
            while (true) {
                val length = inputStream.read(buffer)
                if (length == -1) break
                result.write(buffer, 0, length)
            }
            return result.toString("UTF-8")
        } finally {
            try {
                result.close()
            } catch (ignored: IOException) { }
        }
    }

    fun fromString(string: String): InputStream {
        val bytes = (string as java.lang.String).getBytes("UTF-8")
        return ByteArrayInputStream(bytes)
    }
}
