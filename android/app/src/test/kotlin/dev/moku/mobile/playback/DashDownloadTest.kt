package dev.moku.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class DashDownloadTest {
    private val url = "https://cdn.example/video/ep2/manifest.mpd"

    @Test
    fun timelineTemplatesListEverySegment() {
        val mpd = """<?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT25M3.0S">
              <Period id="0" start="PT0.0S">
                <AdaptationSet contentType="video">
                  <Representation id="0" mimeType="video/mp4" bandwidth="558394">
                    <SegmentTemplate timescale="24000" initialization="init${'$'}RepresentationID${'$'}.m4s" media="${'$'}RepresentationID${'$'}-seg_${'$'}Number%08d${'$'}.m4s" startNumber="1">
                      <SegmentTimeline><S t="0" d="120120" r="299"/><S d="37037"/></SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                  <Representation id="9" mimeType="video/mp4" bandwidth="100"/>
                </AdaptationSet>
                <AdaptationSet contentType="audio">
                  <Representation id="1" mimeType="audio/mp4" bandwidth="96000">
                    <SegmentTemplate timescale="44100" initialization="init${'$'}RepresentationID${'$'}.m4s" media="${'$'}RepresentationID${'$'}-${'$'}Time${'$'}.m4s">
                      <SegmentTimeline><S t="0" d="219129"/><S d="221191"/></SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>"""
        val plan = DashDownload.plan(mpd, url)
        assertEquals("https://cdn.example/video/ep2/init0.m4s", plan.video.init)
        assertEquals(301, plan.video.media.size)
        assertEquals("https://cdn.example/video/ep2/0-seg_00000001.m4s", plan.video.media.first())
        assertEquals("https://cdn.example/video/ep2/0-seg_00000301.m4s", plan.video.media.last())
        assertEquals(listOf("https://cdn.example/video/ep2/1-0.m4s", "https://cdn.example/video/ep2/1-219129.m4s"), plan.audio!!.media)
    }

    @Test
    fun fixedDurationTemplatesAndSingleFiles() {
        val mpd = """<MPD type="static" mediaPresentationDuration="PT10S"><Period>
              <AdaptationSet mimeType="video/mp4"><Representation id="v" bandwidth="1">
                <SegmentTemplate timescale="1000" duration="4000" media="v_${'$'}Number${'$'}.m4s" initialization="v_init.m4s"/>
              </Representation></AdaptationSet>
              <AdaptationSet mimeType="audio/mp4"><Representation id="a" bandwidth="1"><BaseURL>audio/full.mp4</BaseURL></Representation></AdaptationSet>
            </Period></MPD>"""
        val plan = DashDownload.plan(mpd, url)
        assertEquals(listOf("v_1.m4s", "v_2.m4s", "v_3.m4s").map { "https://cdn.example/video/ep2/$it" }, plan.video.media)
        assertNull(plan.audio!!.init)
        assertEquals(listOf("https://cdn.example/video/ep2/audio/full.mp4"), plan.audio!!.media)
    }

    @Test(expected = IOException::class)
    fun refusesProtectedStreams() {
        DashDownload.plan(
            """<MPD type="static" mediaPresentationDuration="PT10S"><Period><AdaptationSet contentType="video">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011"/><Representation id="v" bandwidth="1"/>
            </AdaptationSet></Period></MPD>""",
            url,
        )
    }
}
