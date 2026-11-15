package com.cleantv.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelGrouperTest {
    private fun lib(vararg names: String) = ChannelGrouper.group(
        listOf(Category("1", "UK | SPORTS HD"), Category("2", "UK | SPORTS FHD")),
        names.mapIndexed { i, n -> Stream(i, n, if (i % 2 == 0) "1" else "2", null, i) },
        minPrefixCount = 2,
    )

    @Test
    fun groupsQualityVariantsIntoOneChannel() {
        val l = lib(
            "UK: Sky Sports Main Event FHD", "UK: Sky Sports Main Event HD",
            "UK: Sky Sports Main Event UHD", "UK: Sky Sports Main Event ᴴᴰ",
            "UK: Sky Sports F1 HD",
        )
        assertEquals(listOf("Sky Sports Main Event", "Sky Sports F1"), l.channels.map { it.name })
        assertEquals(1, l.categories.size)
        val main = l.channels.first()
        assertEquals(Quality.HD, ChannelGrouper.playOrder(main, Quality.HD).first().quality)
        assertEquals(Quality.UHD, ChannelGrouper.playOrder(main, Quality.UHD).first().quality)
    }

    @Test
    fun keepsPlusOneAndPlusChannelsSeparate() {
        val l = lib("UK: Channel 4 HD", "UK: Channel 4 +1 HD", "UK: Sky Sports+ HD", "UK: Sky Sports HD")
        assertEquals(4, l.channels.size)
    }

    @Test
    fun fallsBackWhenNoHd() {
        val l = lib("UK: TNT Sports 1 FHD", "UK: TNT Sports 1 SD")
        assertEquals(Quality.FHD, ChannelGrouper.playOrder(l.channels.first(), Quality.HD).first().quality)
    }

    @Test
    fun handlesRealProviderNaming() {
        val names = arrayOf(
            "UK: TNT Sport 1 FHD", "UK: TNT Sports 1 HD", "UK: TNT Sport 1 FHD HEVC",
            "UKFHD Premier Sports 1 UK VERSION", "UK: Premier Sports 1 HD", "Premier Sports 1 HD UK",
            "UKHD Premier Sports  1 HD ( Irish Version )", "UK: Premier Sport 1 (IRE)", "HEVC FHD Premier Sports 1 Ireland",
            "Sky Atlantic FHD UK", "UK: Sky Atlantic HD", "UK: Sky Sports Main Event FHD HEVC 25fps", "UK: Sky Sports Main Event HD",
        )
        val l = lib(*names)
        assertEquals(
            listOf("TNT Sport 1", "Premier Sports 1", "Premier Sports 1 (Ireland)", "Sky Atlantic", "Sky Sports Main Event"),
            l.channels.map { it.name },
        )
        assertEquals(3, l.channels[1].variants.size)
        assertEquals(Quality.FHD, l.channels[1].variants.first().quality)
    }
}
