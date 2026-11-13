package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.data.cloud.VaultCrypto
import fr.yannickuhrig.vyzo.data.db.PlaylistKeys
import fr.yannickuhrig.vyzo.data.db.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le site de gestion du compte (web/src/vault.ts) refait en JavaScript le chiffrement et l'identifiant des playlists.
 * Les valeurs ci-dessous viennent de lui : si l'appli changeait sa façon de faire, le site ne s'entendrait plus avec elle.
 */
class WebCompatTest {

    @Test
    fun `identifiant de playlist identique au site`() {
        val cases = listOf(
            Triple(SourceType.XTREAM, listOf("http://ex.com:8080", " Jean.Dupont ", ""), "643d1967e1c6"),
            Triple(SourceType.STALKER, listOf("http://portail.tv/c/", "", "00:1a:79:ab:cd:ef"), "22fc07a7cb17"),
            Triple(SourceType.M3U, listOf("HTTP://Example.COM:80/list/playlist.m3u?user=A&pass=B", "", ""), "a1aef7764fb9"),
            Triple(SourceType.M3U, listOf("example.com:8443/path/", "", ""), "b45ef1053d6e"),
            Triple(SourceType.M3U, listOf("https://exa_mple.com/x.m3u", "", ""), "4abeb593a48f"),
            Triple(SourceType.M3U, listOf("http://ex.com/a b.m3u", "", ""), "f0d4481642fa"),
            Triple(SourceType.M3U, listOf("https://cdn.ex.com:443/get.php?username=u&password=p&type=m3u_plus#frag", "", ""), "7b106d8ca985"),
        )
        for ((type, args, expected) in cases) {
            assertEquals("$type ${args[0]}", expected, PlaylistKeys.of(type, args[0], args[1], args[2]))
        }
    }

    @Test
    fun `bloc chiffré par le site lisible par l'appli`() {
        val key = ByteArray(32) { it.toByte() }
        val sealed = "v1.gTubNMJK9AgUZAK+16aOMUoeCYmem+iYornWYxXy6bbN+c2c/aqbrMetx5xWjO5yId19FM6kmmCIH9Wyxm8="
        assertEquals("{\"name\":\"Test é\",\"type\":\"XTREAM\"}", VaultCrypto.open(key, sealed, "vyzo|u1|playlist|abc").decodeToString())
    }

    @Test
    fun `clé tirée du mot de passe identique au site`() {
        val salt = ByteArray(16) { (100 + it).toByte() }
        assertEquals("HTcHyTFRi96zuxSl5vE33zPbLTUavdpjWvzpxdTmviI=", VaultCrypto.encode(VaultCrypto.deriveKey("mot de passe", salt, 1000)))
    }

    /** Bloc fait par l'appli, relu par le test du site (web/src/vault.test.ts). */
    @Test
    fun `bloc de l'appli pour le site`() {
        val key = ByteArray(32) { (31 - it).toByte() }
        val sealed = VaultCrypto.seal(key, "{\"title\":\"Le Bureau des légendes\",\"subtitle\":\"S1 · É2\"}".toByteArray(), "vyzo|u2|meta|abc:vod:42")
        println("BLOC_APPLI $sealed")
        assertEquals("Le Bureau des légendes", VaultCrypto.open(key, sealed, "vyzo|u2|meta|abc:vod:42").decodeToString().substringAfter("title\":\"").substringBefore('"'))
    }
}
