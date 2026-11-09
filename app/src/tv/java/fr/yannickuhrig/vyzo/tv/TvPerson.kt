// Dérivé de CastUi.kt d'IPVIO Stream, Copyright (C) 2026 IPVIO Stream contributors, GNU GPL 3.
// Modifié pour Vyzo le 2026-11-08 : les titres viennent du catalogue de Vyzo, sans biographie, libellés en français.
package fr.yannickuhrig.vyzo.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.home.VodItem

/** Nombre de titres disponibles, pour l'en-tête de la page d'un acteur. */
internal fun personCount(movies: Int, series: Int): String {
    val total = movies + series
    return when (total) {
        0 -> "Aucun titre dans ton abonnement"
        1 -> "1 titre dans ton abonnement"
        else -> "$total titres dans ton abonnement"
    }
}

/** Page d'un acteur ou d'un réalisateur : ses titres disponibles dans l'abonnement. */
@Composable
internal fun TvPersonPage(person: String, photo: String?, onOpenMovie: (String) -> Unit, onOpenSeries: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    var found by remember(person) { mutableStateOf<Pair<List<MovieEntity>, List<SeriesEntity>>?>(null) }
    LaunchedEffect(person) { found = c.repo.findContentByPerson(person) }
    val first = remember { FocusRequester() }
    LaunchedEffect(found) { if (found?.let { it.first.isNotEmpty() || it.second.isNotEmpty() } == true) focusWhenReady(first) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = dpx(64), vertical = dpx(48))) {
                item(key = "head") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(30))) {
                        Box(Modifier.size(dpx(150)).clip(CircleShape).background(Surf2), contentAlignment = Alignment.Center) {
                            Text(initialsOf(person), color = Tok.TextLow, fontSize = spx(44), fontWeight = FontWeight.Bold, maxLines = 1)
                            if (!photo.isNullOrBlank()) NetImage(photo, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(dpx(8))) {
                            Text(person, color = Tok.TextHi, fontSize = spx(48), fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(found?.let { personCount(it.first.size, it.second.size) } ?: "Recherche…", color = Tok.TextLow, fontSize = spx(20), maxLines = 1)
                        }
                    }
                }
                val result = found
                if (result != null) {
                    val movies = result.first.map { it.toVodItem() }
                    val series = result.second.map { it.toVodItem() }
                    if (movies.isNotEmpty()) item(key = "movies") {
                        PersonRow("Films", movies, first) { onOpenMovie(it.uid) }
                    }
                    if (series.isNotEmpty()) item(key = "series") {
                        PersonRow("Séries", series, first.takeIf { movies.isEmpty() }) { onOpenSeries(it.uid) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonRow(title: String, list: List<VodItem>, firstFocus: FocusRequester?, open: (VodItem) -> Unit) {
    Column(Modifier.padding(top = dpx(34))) {
        Text(title, color = Tok.TextHi, fontSize = spx(26), fontWeight = FontWeight.Bold, maxLines = 1)
        LazyRow(
            contentPadding = PaddingValues(vertical = dpx(18), horizontal = dpx(10)),
            horizontalArrangement = Arrangement.spacedBy(dpx(18)),
        ) {
            items(list, key = { it.key }) { item ->
                TitledPoster(item, Modifier.width(dpx(180)), onClick = { open(item) }, requester = firstFocus.takeIf { item === list.first() })
            }
        }
    }
}
