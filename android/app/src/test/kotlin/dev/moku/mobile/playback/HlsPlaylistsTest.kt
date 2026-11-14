package dev.moku.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistsTest {
    private val base = "https://cdn.example/show/ep1/index.m3u8"

    @Test
    fun dropsAdSegmentsWithTheirTags() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:5.0,
            https://googlesyndication.example/ad1.ts
            #EXTINF:10.0,
            seg1.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:10.0,
            seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val out = HlsPlaylists.withoutAds(playlist, base)
        assertFalse("ad1.ts" in out)
        assertEquals(2, out.lines().count { it.startsWith("#EXTINF") })
        assertTrue("seg1.ts" in out && "seg2.ts" in out && "#EXT-X-DISCONTINUITY" in out)
    }

    @Test
    fun keepsEverythingWhenTheEpisodeIsOnAdHosts() {
        val playlist = "#EXTM3U\n#EXTINF:10,\nhttps://ibyteimg.com/a.ts\n#EXTINF:10,\nhttps://ibyteimg.com/b.ts\n"
        assertEquals(playlist, HlsPlaylists.withoutAds(playlist, base))
    }

    @Test
    fun resolvesRelativeSegments() {
        assertEquals("https://cdn.example/show/ep1/seg1.ts", HlsPlaylists.resolve(base, "seg1.ts"))
        assertEquals("https://cdn.example/other/seg.ts", HlsPlaylists.resolve(base, "/other/seg.ts"))
    }

    @Test
    fun findsTransportStreamBehindAnImage() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(60)
        val ts = ByteArray(188 * 3).also { for (i in 0 until 3) it[i * 188] = 0x47 }
        assertEquals(64, HlsPlaylists.tsStart(png + ts))
        assertEquals(-1, HlsPlaylists.tsStart(png))
    }
}
