package fr.yannickuhrig.vyzo.data.weather

import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.util.asArr
import fr.yannickuhrig.vyzo.util.asObj
import fr.yannickuhrig.vyzo.util.float
import fr.yannickuhrig.vyzo.util.int
import fr.yannickuhrig.vyzo.util.obj
import fr.yannickuhrig.vyzo.util.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** Ville choisie pour la météo de l'accueil. */
data class WeatherPlace(val name: String, val lat: Double, val lon: Double, val country: String = "")

/** Météo d'un jour : [code] au format WMO (0 = ciel clair, 61 = pluie…). */
data class WeatherDay(val date: String, val min: Float, val max: Float, val code: Int)

data class Weather(val temperature: Float, val code: Int, val days: List<WeatherDay>, val fetchedAt: Long)

/**
 * Météo de l'écran d'accueil, via Open-Meteo (gratuit, sans clé ni compte) : seules les coordonnées de la ville
 * choisie sont envoyées. Mise en cache 30 minutes.
 */
class WeatherService {
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: Pair<String, Weather>? = null

    /** Villes correspondant à [query] (« Lyon », « Bruxelles »…), les plus probables d'abord. */
    suspend fun searchPlaces(query: String): List<WeatherPlace> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=5&language=fr&format=json&name=" + URLEncoder.encode(q, "UTF-8")
        return parsePlaces(Http.getString(url, Http.DEFAULT_UA))
    }

    suspend fun forecast(place: WeatherPlace, now: Long = System.currentTimeMillis()): Weather {
        val key = "${place.lat},${place.lon}"
        cache?.let { (k, w) -> if (k == key && now - w.fetchedAt < CACHE_MS) return w }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=4"
        val w = parseForecast(Http.getString(url, Http.DEFAULT_UA), now)
        cache = key to w
        return w
    }

    fun parsePlaces(body: String): List<WeatherPlace> {
        val root = json.parseToJsonElement(body).asObj() ?: return emptyList()
        return root["results"].asArr().orEmpty().mapNotNull { e ->
            val o = e.asObj() ?: return@mapNotNull null
            val lat = o["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = o["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val region = o.str("admin1")
            val country = listOf(region, o.str("country")).filter { it.isNotBlank() }.distinct().joinToString(", ")
            WeatherPlace(o.str("name"), lat, lon, country)
        }
    }

    fun parseForecast(body: String, now: Long): Weather {
        val root = json.parseToJsonElement(body).asObj() ?: error("Réponse météo illisible")
        val current: JsonObject = root.obj("current") ?: error("Réponse météo incomplète")
        val daily = root.obj("daily")
        val dates = daily?.get("time").asArr().orEmpty().map { it.jsonPrimitive.content }
        fun floats(k: String) = daily?.get(k).asArr().orEmpty().map { it.jsonPrimitive.content.toFloatOrNull() ?: 0f }
        val maxs = floats("temperature_2m_max")
        val mins = floats("temperature_2m_min")
        val codes = daily?.get("weather_code").asArr().orEmpty().map { it.jsonPrimitive.content.toFloatOrNull()?.toInt() ?: 0 }
        val days = dates.indices.map { i -> WeatherDay(dates[i], mins.getOrElse(i) { 0f }, maxs.getOrElse(i) { 0f }, codes.getOrElse(i) { 0 }) }
        return Weather(current.float("temperature_2m"), current.int("weather_code"), days, now)
    }

    companion object {
        private const val CACHE_MS = 30 * 60_000L

        /** Libellé français d'un code météo WMO. */
        fun label(code: Int): String = when (code) {
            0 -> "Ciel clair"
            1 -> "Plutôt dégagé"
            2 -> "Partiellement nuageux"
            3 -> "Couvert"
            45, 48 -> "Brouillard"
            in 51..57 -> "Bruine"
            in 61..67 -> "Pluie"
            in 71..77 -> "Neige"
            in 80..82 -> "Averses"
            85, 86 -> "Averses de neige"
            in 95..99 -> "Orage"
            else -> "—"
        }
    }
}
