package dev.moku.mobile.playback

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

// Joins a video-only file and an audio-only file into one MP4, for streams that keep them apart.
internal object TrackMuxer {
    private class Input(val extractor: MediaExtractor, val track: Int, var done: Boolean = false)

    fun mux(video: File, audio: File, out: File) {
        val inputs = listOf(open(video, "video/"), open(audio, "audio/"))
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val formats = inputs.map { it.extractor.getTrackFormat(it.track) }
            val tracks = formats.map(muxer::addTrack)
            val capacity = formats.maxOf { if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0 }
                .coerceAtLeast(4 * 1024 * 1024)
            val buffer = ByteBuffer.allocate(capacity)
            val info = MediaCodec.BufferInfo()
            muxer.start()
            while (true) {
                // Interleaved by time, so neither track runs far ahead of the other in the file.
                val next = inputs.filterNot { it.done }.minByOrNull { it.extractor.sampleTime } ?: break
                val size = next.extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    next.done = true
                    continue
                }
                val key = next.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, size, next.extractor.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(tracks[inputs.indexOf(next)], buffer, info)
                next.extractor.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            inputs.forEach { it.extractor.release() }
        }
    }

    private fun open(file: File, mimePrefix: String): Input {
        val extractor = MediaExtractor().apply { setDataSource(file.path) }
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(mimePrefix)
        } ?: run {
            extractor.release()
            throw IOException("The ${mimePrefix.trimEnd('/')} track couldn't be read")
        }
        extractor.selectTrack(track)
        return Input(extractor, track)
    }
}
