// Dérivé de DetailScreen.kt d'IPVIO Stream, Copyright (C) 2026 IPVIO Stream contributors, GNU GPL 3.
// Modifié pour Vyzo le 2026-11-08 : fiche branchée sur les ViewModel de Vyzo (reprise, favoris, téléchargements, cast, lecteur externe, filmographies), libellés en français.
package fr.yannickuhrig.vyzo.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fr.yannickuhrig.vyzo.data.db.DownloadEntity
import fr.yannickuhrig.vyzo.data.remote.CastCommand
import fr.yannickuhrig.vyzo.data.tmdb.CastMember
import fr.yannickuhrig.vyzo.ui.home.VodItem
import fr.yannickuhrig.vyzo.ui.vod.detailBadges
import fr.yannickuhrig.vyzo.data.tmdb.TmdbEnrichment
import fr.yannickuhrig.vyzo.ui.common.CastDialog
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.Loading
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.common.START_FROM_BEGINNING
import fr.yannickuhrig.vyzo.ui.common.appViewModel
import fr.yannickuhrig.vyzo.ui.common.openInExternalPlayer
import fr.yannickuhrig.vyzo.ui.vod.MovieDetailViewModel
import fr.yannickuhrig.vyzo.ui.vod.TrailerDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Taille du titre d'une fiche : énorme quand il est court, plus raisonnable quand il prendrait trois lignes. */
fun detailTitleSize(title: String): Int = if (title.length <= 22) 84 else if (title.length <= 40) 64 else 50

/** Un film se reprend après 30 secondes de visionnage, et jusqu'à 95 % de sa durée. */
fun isResumable(positionMs: Long, durationMs: Long): Boolean = positionMs > 30_000 && positionMs < durationMs * 0.95

/** Noms séparés par des virgules ou des points-virgules, tels que les fournit l'abonnement. */
fun personNames(text: String): List<String> = text.split(",", ";").map { it.trim() }.filter { it.isNotBlank() }

/** Distribution de TMDB si elle est connue, sinon celle de l'abonnement. */
fun castFrom(actors: String, enrichment: TmdbEnrichment?): List<CastMember> =
    enrichment?.cast.orEmpty().ifEmpty { personNames(actors).map { CastMember(0, it, "", null) } }

/** Libellé et icône du bouton de téléchargement d'un film. */
fun downloadAction(d: DownloadEntity?): Pair<String, ImageVector> = when {
    d == null -> "Télécharger" to Icons.Default.ArrowDownward
    d.isDownloading -> "${(d.progress * 100).toInt()} %" to Icons.Default.Pause
    d.isQueued -> "En attente…" to Icons.Default.ArrowDownward
    d.isPaused -> "En pause (${(d.progress * 100).toInt()} %)" to Icons.Default.PlayArrow
    d.isCompleted -> "Téléchargé" to Icons.Default.Check
    else -> "Échec, réessayer" to Icons.Default.Refresh
}

/** Essaie de donner le focus, le temps que l'élément apparaisse à l'écran. */
internal suspend fun focusWhenReady(target: FocusRequester): Boolean {
    repeat(20) {
        delay(100)
        if (runCatching { target.requestFocus() }.getOrDefault(false)) return true
    }
    return false
}

/** Ramène la liste sur la ligne [index] quand la télécommande y entre : le titre reste visible, le panneau reste entier. */
@Composable
internal fun Modifier.scrollsToOnFocus(list: LazyListState, index: Int): Modifier {
    val scope = rememberCoroutineScope()
    var inside by remember { mutableStateOf(false) }
    return onFocusChanged { state ->
        if (state.hasFocus && !inside) scope.launch { list.animateScrollToItem(index) }
        inside = state.hasFocus
    }
}

private val DetailScrimX = listOf(0f to 1f, 0.3f to .92f, 0.55f to .5f, 0.8f to 0f)
private val DetailScrimY = listOf(0f to .5f, 0.2f to 0f, 0.55f to .78f, 0.8f to 1f)

/** Fond d'une fiche : l'image en plein écran, fondue vers la gauche et vers le bas, puis assombrie dès qu'on descend. */
@Composable
internal fun DetailPage(backdrop: String, list: LazyListState, content: LazyListScope.() -> Unit) {
    val scrolled by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val dim by animateFloatAsState(if (scrolled) 0.86f else 0f, label = "detaildim")
    Box(Modifier.fillMaxSize().background(Bg)) {
        if (backdrop.isNotBlank()) {
            AsyncImage(cardBackdrop(backdrop), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd)
        }
        BakedScrim(DetailScrimX, DetailScrimY, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Bg.copy(alpha = dim)))
        LazyColumn(
            Modifier.fillMaxSize(), state = list,
            contentPadding = PaddingValues(horizontal = 36.dp, vertical = DetailVerticalPad),
            verticalArrangement = Arrangement.spacedBy(dpx(28)),
            content = content,
        )
        // Le bas des boutons du haut de fiche resterait visible dans la marge, au-dessus du panneau.
        Box(Modifier.fillMaxWidth().height(DetailVerticalPad).background(Bg.copy(alpha = dim / 0.86f)))
    }
}

/** Marge haute et basse d'une fiche : le panneau des épisodes occupe tout le reste de l'écran. */
internal val DetailVerticalPad = 28.dp

/** Haut de fiche : genre de contenu, titre, informations, résumé, puis les boutons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailHero(
    kicker: String,
    title: String,
    meta: List<String>,
    rating: Float,
    tagline: String?,
    plot: String,
    modifier: Modifier = Modifier,
    badges: List<String> = emptyList(),
    resume: @Composable () -> Unit = {},
    below: @Composable () -> Unit = {},
    buttons: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth().heightIn(min = dpx(560)), contentAlignment = Alignment.BottomStart) {
        Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
            Kicker(kicker)
            Text(
                title, color = Tok.TextHi, fontSize = spx(detailTitleSize(title)), lineHeight = spx(detailTitleSize(title) * 1.08f),
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = dpx(1300)),
            )
            DetailMeta(meta, rating)
            if (badges.isNotEmpty()) Text(badges.joinToString("  ·  "), color = Tok.TextMid, fontSize = spx(18), maxLines = 1)
            resume()
            if (!tagline.isNullOrBlank()) {
                Text("« $tagline »", color = Accent2, fontSize = spx(18), fontStyle = FontStyle.Italic, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = dpx(1100)))
            }
            if (plot.isNotBlank()) Overview(plot)
            FlowRow(
                Modifier.padding(vertical = dpx(6)),
                horizontalArrangement = Arrangement.spacedBy(dpx(14)),
                verticalArrangement = Arrangement.spacedBy(dpx(14)),
            ) { buttons() }
            below()
        }
    }
}

@Composable
private fun DetailMeta(parts: List<String>, rating: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
        parts.forEachIndexed { i, p ->
            if (i > 0) Separator()
            Text(p, color = Color(0xFFD4D4DA), fontSize = spx(18), maxLines = 1)
        }
        if (rating > 0f) {
            if (parts.isNotEmpty()) Separator()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(5))) {
                Icon(Icons.Default.Star, null, Modifier.size(dpx(19)), tint = Accent1)
                Text("%.1f".format(rating), color = Accent1, fontSize = spx(18), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Barre de reprise et temps restant d'un film commencé. */
@Composable
private fun ResumeBar(positionMs: Long, durationMs: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
        Box(Modifier.width(dpx(180)).height(dpx(4)).clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxWidth(positionMs.toFloat() / durationMs).height(dpx(4)).background(Accent1))
        }
        Text("Reste ${Fmt.minutes(((durationMs - positionMs) / 1000).toInt())}", color = Tok.TextLow, fontSize = spx(16), maxLines = 1)
    }
}

/** Résumé sur quatre lignes ; un bouton l'ouvre en entier seulement s'il dépasse. */
@Composable
private fun Overview(text: String) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    var clipped by remember(text) { mutableStateOf(false) }
    Text(
        text, color = Tok.TextMid, fontSize = spx(18), lineHeight = spx(26),
        maxLines = if (open) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!open) clipped = it.hasVisualOverflow },
        modifier = Modifier.widthIn(max = dpx(1100)),
    )
    if (clipped) TextPill(if (open) "Réduire" else "Lire la suite") { open = !open }
}

/** Petite pastille de texte sélectionnable à la télécommande. */
@Composable
internal fun TextPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Text(
        text, color = if (f) Tok.FocusInk else Tok.TextHi, fontSize = spx(15), fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = modifier.clip(shape).background(if (f) Tok.FocusFill else Color(0x22FFFFFF))
            .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick)
            .padding(horizontal = dpx(18), vertical = dpx(7)),
    )
}

/** « Réalisation » suivie de ses noms ; chacun ouvre la liste de ses titres disponibles. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Directors(names: List<String>, onPerson: (String) -> Unit) {
    if (names.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(dpx(10)), verticalArrangement = Arrangement.spacedBy(dpx(8)), itemVerticalAlignment = Alignment.CenterVertically) {
        Text("Réalisation", color = Tok.TextLow, fontSize = spx(16), maxLines = 1)
        names.forEach { n -> TextPill(n) { onPerson(n) } }
    }
}

/** Titres recommandés par TMDB et présents dans l'abonnement. */
@Composable
internal fun SimilarRow(list: List<VodItem>, open: (VodItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(dpx(14))) {
        Text("Titres similaires", color = Tok.TextHi, fontSize = spx(24), fontWeight = FontWeight.Bold, maxLines = 1)
        LazyRow(contentPadding = PaddingValues(vertical = dpx(14), horizontal = dpx(10)), horizontalArrangement = Arrangement.spacedBy(dpx(18))) {
            items(list, key = { it.key }) { item -> TitledPoster(item, Modifier.width(dpx(180)), onClick = { open(item) }) }
        }
    }
}

/** Rangée de la distribution. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PeopleRow(cast: List<CastMember>, onPerson: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(dpx(14))) {
        Text("Distribution", color = Tok.TextHi, fontSize = spx(24), fontWeight = FontWeight.Bold, maxLines = 1)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(20)), contentPadding = PaddingValues(vertical = dpx(10), horizontal = dpx(8))) {
            items(cast, key = { "${it.id}:${it.name}" }) { p -> PersonCard(p) { onPerson(p.name) } }
        }
    }
}

@Composable
private fun PersonCard(p: CastMember, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.08f) else 1f, label = "person")
    Column(
        Modifier.width(dpx(150)).graphicsLayer { scaleX = s; scaleY = s }
            .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dpx(6)),
    ) {
        Box(
            Modifier.size(dpx(120)).focusGlow(f, CircleShape).clip(CircleShape).background(Surf2)
                .border(if (f) 2.dp else 1.dp, if (f) Tok.FocusFill else Tok.Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initialsOf(p.name), color = Tok.TextLow, fontSize = spx(30), fontWeight = FontWeight.Bold, maxLines = 1)
            if (!p.profileUrl.isNullOrBlank()) NetImage(p.profileUrl!!, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Text(p.name, color = if (f) Tok.TextHi else Tok.TextMid, fontSize = spx(15), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (p.character.isNotBlank()) Text(p.character, color = Tok.TextLow, fontSize = spx(14), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Envoi de la lecture vers les autres écrans Vyzo du compte. */
@Composable
internal fun CastPicker(
    itemType: String,
    itemId: String,
    title: String,
    poster: String,
    positionMs: Long,
    onClose: () -> Unit,
) {
    val c = LocalContainer.current
    CastDialog(
        onSend = {
            onClose()
            c.cloud.sendCastCommand(
                CastCommand(
                    itemType = itemType,
                    itemId = itemId,
                    title = title,
                    posterUrl = poster,
                    positionMs = positionMs,
                    senderDeviceId = c.platform.deviceModel,
                ),
            )
        },
        onDismiss = onClose,
    )
}

/** Fiche d'un film à la télécommande. */
@Composable
fun TvMovieDetail(
    uid: String,
    onPlay: (uid: String, startMs: Long) -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    val c = LocalContainer.current
    val vm = appViewModel(key = "movie-$uid") { MovieDetailViewModel(it, uid) }
    val movie by vm.movie.collectAsState()
    val progress by vm.progress.collectAsState()
    val fav by vm.favorite.collectAsState()
    val enrichment by vm.enrichment.collectAsState()
    val ratings by vm.ratings.collectAsState()
    val similar by vm.similar.collectAsState()
    val download by vm.download.collectAsState()
    val m = movie ?: return Loading()

    val resumeMs = progress?.takeIf { isResumable(it.positionMs, it.durationMs) }?.positionMs
    var showCast by remember { mutableStateOf(false) }
    var showTrailer by remember { mutableStateOf(false) }
    var person by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusWhenReady(play) }

    val cast = remember(m.actors, enrichment?.cast) { castFrom(m.actors, enrichment) }
    val directors = enrichment?.directors?.filter { it.isNotBlank() }.orEmpty().ifEmpty { personNames(m.director) }

    DetailPage(enrichment?.backdropUrl ?: m.backdrop.ifBlank { m.poster }, list) {
        item(key = "hero") {
            DetailHero(
                kicker = "FILM",
                title = m.name,
                meta = listOf(m.year, Fmt.minutes(m.durationSecs), m.genre.ifBlank { enrichment?.genres?.joinToString(" · ").orEmpty() }).filter { it.isNotBlank() },
                rating = if (m.rating > 0f) m.rating else enrichment?.voteAverage ?: 0f,
                tagline = enrichment?.tagline,
                badges = detailBadges(enrichment?.certification, ratings),
                plot = m.plot.ifBlank { enrichment?.overview.orEmpty() },
                modifier = Modifier.scrollsToOnFocus(list, 0),
                resume = { if (resumeMs != null) ResumeBar(resumeMs, progress!!.durationMs) },
                below = { Directors(directors) { person = it } },
            ) {
                if (resumeMs != null) {
                    HeroButton("Reprendre à ${Fmt.duration(resumeMs)}", Icons.Default.PlayArrow, true, Modifier.focusRequester(play), h = 44) { onPlay(uid, resumeMs) }
                    HeroButton("Depuis le début", Icons.Default.Replay, false, h = 44) { onPlay(uid, START_FROM_BEGINNING) }
                } else {
                    HeroButton("Lire", Icons.Default.PlayArrow, true, Modifier.focusRequester(play), h = 44) { onPlay(uid, 0) }
                }
                HeroButton("Bande-annonce", Icons.Default.Movie, false, h = 44) { showTrailer = true }
                val (label, icon) = downloadAction(download)
                HeroButton(label, icon, false, h = 44) { vm.toggleDownload() }
                HeroButton("Caster", Icons.Default.Cast, false, h = 44) { showCast = true }
                HeroButton("Autre lecteur", Icons.AutoMirrored.Filled.OpenInNew, false, h = 44) {
                    openInExternalPlayer(c, m.name, startMs = resumeMs ?: 0) { c.repo.resolveMovie(m) }
                }
                HeroButton(if (fav) "Favori" else "Ajouter aux favoris", if (fav) Icons.Default.Star else Icons.Default.StarBorder, false, h = 44) { vm.toggleFavorite() }
            }
        }
        if (cast.isNotEmpty()) item(key = "cast") { PeopleRow(cast) { person = it } }
        if (similar.isNotEmpty()) item(key = "similar") { SimilarRow(similar.map { it.toVodItem() }) { onOpenMovie(it.uid) } }
    }

    if (showTrailer) TrailerDialog(title = m.name, youtubeId = enrichment?.youtubeTrailerId, onDismiss = { showTrailer = false })
    if (showCast) {
        CastPicker("movie", uid, m.name, m.poster, progress?.positionMs ?: 0L, { showCast = false })
    }
    person?.let { p ->
        TvPersonPage(
            person = p,
            photo = cast.firstOrNull { it.name == p }?.profileUrl,
            onOpenMovie = { person = null; onOpenMovie(it) },
            onOpenSeries = { person = null; onOpenSeries(it) },
            onDismiss = { person = null },
        )
    }
}
