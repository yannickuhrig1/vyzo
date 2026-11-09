package fr.yannickuhrig.vyzo

import android.util.Xml
import fr.yannickuhrig.vyzo.data.epg.XmltvParser
import fr.yannickuhrig.vyzo.data.epg.XmltvProgramme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** L'analyseur XML d'Android traite le DOCTYPE par défaut, contrairement à kxml2 sur PC. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class XmltvAndroidTest {
    @Test
    fun lesEntitesHtmlSurviventAuDoctype() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<tv><programme start="20261027200000" stop="20261027210000" channel="a"><title>L&eacute;gende</title><desc>Un&nbsp;film d&rsquo;&eacute;t&eacute;</desc></programme></tv>"""
        val found = ArrayList<XmltvProgramme>()
        XmltvParser { Xml.newPullParser() }.parse(xml.byteInputStream(), onChannel = {}, onProgramme = { found += it })
        assertEquals("Légende", found.single().title)
        assertEquals("Un film d’été", found.single().description)
    }
}
