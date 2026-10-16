package app.olauncher.helper

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Providers may report no size, or an inaccurate size: enforce the limit while reading. */
internal fun InputStream.readBoundedBytes(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        require(count <= limit - output.size()) { "File exceeds import limit" }
        output.write(buffer, 0, count)
    }
}
