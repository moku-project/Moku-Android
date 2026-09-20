package dev.moku.mobile.extension.repo

/**
 * Minimal hand-rolled protobuf wire-format reader — no `.proto` schema is published for
 * Keiyoushi's `index.pb`, so there's nothing to run `protoc` against. The field numbers
 * below are lifted directly from Tsunagu's own from-scratch decoder
 * (`backend/internal/repository/protobuf.go`), which already reverse-engineered this
 * exact index format; this is a straight Kotlin port of that reader, not a re-derivation.
 */
class ProtoReader(private val bytes: ByteArray) {
    var pos = 0
        private set

    fun hasRemaining(offsetEnd: Int): Boolean = pos < offsetEnd

    fun readTag(): Pair<Int, Int> {
        val tag = readVarint()
        return (tag ushr 3).toInt() to (tag and 0x7).toInt()
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = bytes[pos].toLong() and 0xFF
            pos++
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0L) break
            shift += 7
        }
        return result
    }

    fun readLengthDelimited(): ByteArray {
        val len = readVarint().toInt()
        val slice = bytes.copyOfRange(pos, pos + len)
        pos += len
        return slice
    }

    /** Wire type 5 (32-bit) or 1 (64-bit) fixed-width fields we don't care about the value of. */
    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> pos += 8
            2 -> readLengthDelimited()
            5 -> pos += 4
            else -> error("Unsupported protobuf wire type $wireType")
        }
    }
}

fun ByteArray.asReader() = ProtoReader(this)
