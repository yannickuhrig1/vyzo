// Dérivé de DetailScreen.kt d'IPVIO Stream, Copyright (C) 2026 IPVIO Stream contributors, GNU GPL 3.
// Modifié pour Vyzo le 2026-11-08 : saisons et épisodes branchés sur SeriesDetailViewModel, téléchargement par épisode, saut vers un épisode, libellés en français.
// Modifié pour Vyzo le 2026-11-11 : épisode marqué vu ou non vu, la page ne suit plus la liste des épisodes (dernier épisode atteignable).
package fr.yannickuhrig.vyzo.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.data.db.DownloadEntity
import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.SeriesScheduleEntity
import fr.yannickuhrig.vyzo.data.tmdb.TmdbEpisode
import fr.yannickuhrig.vyzo.data.vod.SeriesTracking
import fr.yannickuhrig.vyzo.ui.vod.detailBadges
import fr.yannickuhrig.vyzo.data.db.WatchProgressEntity
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.Loading
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.common.TextInputDialog
import fr.yannickuhrig.vyzo.ui.common.appViewModel
import fr.yannickuhrig.vyzo.ui.common.openInExternalPlayer
import fr.yannickuhrig.vyzo.ui.vod.SeriesDetailViewModel
import fr.yannickuhrig.vyzo.ui.vod.TrailerDialog
import fr.yannickuhrig.vyzo.ui.vod.findEpisode

/** À partir de ce nombre d'épisodes, la fiche propose « Aller à l'épisode ». */
const val JUMP_MIN_EPISODES = 40

/** Rang du panneau des saisons dans la fiche, juste sous le haut de page. */
private const val PANE_INDEX = 1

fun seasonLabel(n: Int): String = if (n == 0) "Bonus" else "Saison $n"

fun episodesCount(n: Int): String = if (n <= 1) "$n épisode" else "$n épisodes"

private val genericEpisodeTitle = Regex("^(é|e|É|E)pisode\\s*\\d+$", RegexOption.IGNORE_CASE)

/** Titre d'un épisode, sans répéter « Épisode 3 » quand l'abonnement n'en donne pas de vrai. */
fun episodeTitle(episode: Int, title: String): String =
    if (title.isBlank() || genericEpisodeTitle.matches(title.trim())) "Épisode $episode" else "É$episode · $title"

/** Libellé du bouton « Vu » d'un épisode quand on le sélectionne : ce que fera OK. */
fun watchedActionLabel(watched: Boolean): String = if (watched) "Retirer « Vu »" else "Marquer comme vu"

fun isWatched(positionMs: Long, durationMs: Long): Boolean = durationMs > 0 && positionMs >= durationMs * 0.95

/** Position de reprise d'un épisode : un épisode terminé repart du début. */
fun episodeResumeMs(positionMs: Long, durationMs: Long): Long = if (positionMs < durationMs * 0.95) positionMs else 0

/** Fiche d'une série à la télécommande : saisons à gauche, épisodes à droite. */
@Composable
fun TvSeriesDetail(
    uid: String,
    onPlayEpisode: (EpisodeEntity, Long) -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    val c = LocalContainer.current
    val vm = appViewModel(key = "series-$uid") { SeriesDetailViewModel(it, uid) }
    val series by vm.series.collectAsState()
    val episodes by vm.episodes.collectAsState()
    val progress by vm.progress.collectAsState()
    val fav by vm.favorite.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val seasonSel by vm.season.collectAsState()
    val enrichment by vm.enrichment.collectAsState()
    val ratings by vm.ratings.collectAsState()
    val similar by vm.similar.collectAsState()
    val episodeMeta by vm.episodeMeta.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val qualities by vm.qualities.collectAsState()
    val schedule by vm.schedule.collectAsState()
    val freshSlots by vm.freshSlots.collectAsState()
    val upcomingBySeason by vm.upcomingBySeason.collectAsState()
    val s = series ?: return Loading()

    val eps = episodes.orEmpty()
    val seasons = eps.map { it.season }.distinct().sorted()
    val season = seasonSel ?: seasons.firstOrNull()
    val shown = eps.filter { it.season == season }
    val upcoming = vm.upcomingForSeason(season, shown, upcomingBySeason)
    LaunchedEffect(season) { season?.let(vm::loadSeasonMeta) }
    val resume = vm.resumeTarget(eps, progress)

    var showCast by remember { mutableStateOf(false) }
    var showTrailer by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var person by remember { mutableStateOf<String?>(null) }
    var jumpTo by remember { mutableStateOf<String?>(null) }

    val page = rememberLazyListState()
    val episodeList = rememberLazyListState()
    val firstButton = remember { FocusRequester() }
    val seasonEntry = remember { FocusRequester() }
    val episodeFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun focusOf(id: String) = episodeFocus.getOrPut(id) { FocusRequester() }

    // Survivent à l'aller-retour vers le lecteur : la télécommande reprend sur l'épisode qu'on vient de regarder.
    var lastEpisode by rememberSaveable { mutableStateOf<String?>(null) }
    var backFromEpisode by rememberSaveable { mutableStateOf(false) }
    var listedSeason by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(season) {
        if (listedSeason != null && listedSeason != season) episodeList.scrollToItem(0)
        listedSeason = season
    }
    LaunchedEffect(eps.isNotEmpty(), resume != null) {
        val back = lastEpisode?.takeIf { backFromEpisode && eps.any { e -> e.uid == it } }
        when {
            back != null -> { backFromEpisode = false; focusWhenReady(focusOf(back)) }
            backFromEpisode && eps.isEmpty() -> Unit
            else -> focusWhenReady(firstButton)
        }
    }
    LaunchedEffect(jumpTo, shown) {
        val target = jumpTo ?: return@LaunchedEffect
        val i = shown.indexOfFirst { it.uid == target }
        if (i < 0) return@LaunchedEffect
        page.scrollToItem(PANE_INDEX)
        episodeList.scrollToItem(i)
        focusWhenReady(focusOf(target))
        jumpTo = null
    }

    val cast = remember(s.actors, enrichment?.cast) { castFrom(s.actors, enrichment) }
    val paneHeight = LocalConfiguration.current.screenHeightDp.dp - DetailVerticalPad * 2

    fun goToEpisodes() {
        val target = shown.firstOrNull { it.uid == lastEpisode } ?: shown.firstOrNull() ?: return
        runCatching { focusOf(target.uid).requestFocus() }
    }

    // La page ne suit pas la sélection dans le panneau des épisodes : seule la liste des épisodes défile,
    // et jamais au-delà de ses bouts (sinon la page prenait le relais et É10 devenait inatteignable).
    var paneFocused by remember { mutableStateOf(false) }
    val platformSpec = LocalBringIntoViewSpec.current
    val pageSpec = remember(platformSpec) { FrozenSpec(platformSpec) { paneFocused } }
    val episodePivot = with(LocalDensity.current) { paneHeight.toPx() * 0.3f }
    val episodeSpec = remember(episodePivot) { PivotSpec(episodePivot) { episodeList } }

    CompositionLocalProvider(LocalBringIntoViewSpec provides pageSpec) {
        DetailPage(enrichment?.backdropUrl ?: s.backdrop.ifBlank { s.poster }, page) {
            item(key = "hero") {
                DetailHero(
                    kicker = "SÉRIE",
                    title = s.name,
                    meta = listOf(s.year, s.genre.ifBlank { enrichment?.genres?.joinToString(" · ").orEmpty() }).filter { it.isNotBlank() },
                    rating = if (s.rating > 0f) s.rating else enrichment?.voteAverage ?: 0f,
                    tagline = enrichment?.tagline,
                    badges = detailBadges(enrichment?.certification, ratings),
                    plot = s.plot.ifBlank { enrichment?.overview.orEmpty() },
                    modifier = Modifier.scrollsToOnFocus(page, 0),
                    below = { TvSeriesTrackingRow(freshSlots.size, schedule) },
                ) {
                    if (resume != null) {
                        val (ep, pos) = resume
                        HeroButton(
                            (if (pos > 0) "Reprendre " else "Lire ") + "S${ep.season} É${ep.episode}",
                            Icons.Default.PlayArrow, true, Modifier.focusRequester(firstButton), h = 44,
                        ) { onPlayEpisode(ep, pos) }
                    }
                    HeroButton("Bande-annonce", Icons.Default.Movie, false, if (resume == null) Modifier.focusRequester(firstButton) else Modifier, h = 44) { showTrailer = true }
                    if (shown.isNotEmpty()) {
                        HeroButton("Télécharger ${seasonLabel(season ?: 1).lowercase()}", Icons.Default.ArrowDownward, false, h = 44) { vm.downloadSeason(season ?: 1) }
                    }
                    if (eps.size >= JUMP_MIN_EPISODES) {
                        HeroButton("Aller à l'épisode…", Icons.Default.Search, false, h = 44) { showJump = true }
                    }
                    if (resume != null) {
                        val (ep, pos) = resume
                        HeroButton("Caster", Icons.Default.Cast, false, h = 44) { showCast = true }
                        HeroButton("Autre lecteur", Icons.AutoMirrored.Filled.OpenInNew, false, h = 44) {
                            openInExternalPlayer(c, "${s.name} · S${ep.season} É${ep.episode}", startMs = pos) { c.repo.resolveEpisode(ep) }
                        }
                    }
                    HeroButton(if (fav) "Favori" else "Ajouter aux favoris", if (fav) Icons.Default.Star else Icons.Default.StarBorder, false, h = 44) { vm.toggleFavorite() }
                }
            }

            if (seasons.isEmpty()) {
                item(key = "status") {
                    when {
                        loading -> Box(Modifier.fillMaxWidth().height(dpx(200)), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent1) }
                        error != null -> Text(error!!, color = Color(0xFFFF8A80), fontSize = spx(19))
                        else -> Text("Aucun épisode disponible.", color = Tok.TextLow, fontSize = spx(19))
                    }
                }
            } else {
                item(key = "pane") {
                    Row(
                        Modifier.fillMaxWidth().height(paneHeight)
                            .scrollsToOnFocus(page, PANE_INDEX)
                            .onFocusChanged { paneFocused = it.hasFocus }
                            .focusProperties { onEnter = { runCatching { seasonEntry.requestFocus() } } }
                            .focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(dpx(28)),
                    ) {
                        CompositionLocalProvider(LocalBringIntoViewSpec provides SmoothSpec) {
                            Column(
                                Modifier.width(dpx(270)).fillMaxHeight().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(dpx(4)),
                            ) {
                                seasons.forEach { n ->
                                    val own = remember(n) { FocusRequester() }
                                    SeasonEntry(
                                        label = seasonLabel(n),
                                        count = eps.count { it.season == n },
                                        selected = n == season,
                                        modifier = Modifier.focusRequester(if (n == season) seasonEntry else own),
                                        onFocus = { vm.season.value = n },
                                        onGoToEpisodes = { goToEpisodes() },
                                    )
                                }
                            }
                        }
                        CompositionLocalProvider(LocalBringIntoViewSpec provides episodeSpec) {
                            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(dpx(8))) {
                                Text(
                                    "${seasonLabel(season ?: 1)} · ${episodesCount(shown.size)}",
                                    color = Tok.TextLow, fontSize = spx(17), maxLines = 1,
                                )
                                LazyColumn(
                                    Modifier.fillMaxSize(), state = episodeList,
                                    contentPadding = PaddingValues(vertical = dpx(8), horizontal = dpx(8)),
                                    verticalArrangement = Arrangement.spacedBy(dpx(10)),
                                ) {
                                    items(shown, key = { it.uid }) { ep ->
                                        EpisodeRow(
                                            ep = ep,
                                            progress = progress[ep.uid],
                                            quality = qualities[ep.uid],
                                            tmdb = episodeMeta[ep.season to ep.episode],
                                            isFresh = (ep.season to ep.episode) in freshSlots,
                                            download = downloads[ep.uid],
                                            focus = focusOf(ep.uid),
                                            onFocus = { lastEpisode = ep.uid },
                                            onLeft = { runCatching { seasonEntry.requestFocus() } },
                                            onDownload = { vm.downloadEpisode(ep) },
                                            onToggleWatched = { vm.toggleWatched(ep) },
                                            onPlay = {
                                                lastEpisode = ep.uid
                                                backFromEpisode = true
                                                onPlayEpisode(ep, progress[ep.uid]?.let { episodeResumeMs(it.positionMs, it.durationMs) } ?: 0)
                                            },
                                        )
                                    }
                                    items(upcoming, key = { "upcoming-${it.season}-${it.number}" }) { ep ->
                                        UpcomingTvEpisodeRow(
                                            ep = ep,
                                            focus = focusOf("upcoming-${ep.season}-${ep.number}"),
                                            onLeft = { runCatching { seasonEntry.requestFocus() } },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (cast.isNotEmpty()) item(key = "cast") { PeopleRow(cast) { person = it } }
            if (similar.isNotEmpty()) item(key = "similar") { SimilarRow(similar.map { it.toVodItem() }) { onOpenSeries(it.uid) } }
        }
    }

    if (showJump) {
        TextInputDialog(
            title = "Aller à l'épisode",
            label = "Numéro d'épisode (ou S2E5)",
            keyboardType = KeyboardType.Number,
            confirmLabel = "Aller",
            onConfirm = { text ->
                val ep = findEpisode(eps, season, text)
                if (ep == null) c.platform.toast("Épisode introuvable")
                else {
                    vm.season.value = ep.season
                    jumpTo = ep.uid
                }
            },
            onDismiss = { showJump = false },
        )
    }
    if (showTrailer) TrailerDialog(title = s.name, youtubeId = enrichment?.youtubeTrailerId, onDismiss = { showTrailer = false })
    if (showCast && resume != null) {
        val (ep, pos) = resume
        CastPicker(
            "episode", ep.uid, "${s.name} - S${ep.season} E${ep.episode} - ${ep.title}", ep.poster.ifBlank { s.poster }, pos,
            { showCast = false },
        )
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

/** Une saison : la sélectionner affiche ses épisodes, ▶ ou OK y entre. */
@Composable
private fun SeasonEntry(
    label: String,
    count: Int,
    selected: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onGoToEpisodes: () -> Unit,
) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(14))
    Row(
        modifier.fillMaxWidth().clip(shape).background(if (f) Color(0x24FFFFFF) else Color.Transparent)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { onGoToEpisodes(); true } else false
            }
            .clickable(onClick = onGoToEpisodes)
            .padding(horizontal = dpx(12), vertical = dpx(10)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dpx(14)),
    ) {
        Box(
            Modifier.width(dpx(5)).height(if (selected) dpx(46) else dpx(24))
                .shadow(if (selected) dpx(10) else 0.dp, RoundedCornerShape(50), ambientColor = Accent1, spotColor = Accent1)
                .clip(RoundedCornerShape(50)).background(if (selected) Accent1 else Color(0x33FFFFFF)),
        )
        Column {
            Text(label, color = if (selected || f) Tok.TextHi else Tok.TextMid, fontSize = spx(22), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            Text(episodesCount(count), color = Tok.TextLow, fontSize = spx(15), maxLines = 1)
        }
    }
}

/** Bannières « nouveaux épisodes à voir » et « prochaine diffusion » dans l'en-tête TV. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvSeriesTrackingRow(freshCount: Int, schedule: SeriesScheduleEntity?) {
    val schedDisplay = schedule?.let { SeriesTracking.formatScheduleDisplay(it) }
    val freshLabel = SeriesTracking.alertBannerLabel(freshCount)
    if (freshLabel.isBlank() && schedDisplay == null) return
    FlowRow(
        Modifier.padding(top = dpx(4)),
        horizontalArrangement = Arrangement.spacedBy(dpx(10)),
        verticalArrangement = Arrangement.spacedBy(dpx(10)),
    ) {
        if (freshLabel.isNotBlank()) {
            Text(
                freshLabel,
                color = Color.Black,
                fontSize = spx(16),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Accent1).padding(horizontal = dpx(14), vertical = dpx(5)),
            )
        }
        if (schedDisplay != null) {
            val bg = if (schedDisplay.hasUpcomingDate) Color(0xCC1E3A8A) else Color(0x1FFFFFFF)
            val fg = when {
                schedDisplay.hasUpcomingDate -> Color(0xFFBFDBFE)
                schedDisplay.isEnded -> Tok.TextLow
                else -> Accent2
            }
            Text(
                schedDisplay.banner,
                color = fg,
                fontSize = spx(16),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = dpx(14), vertical = dpx(5)),
            )
        }
    }
}

/** Un épisode : image, titre, résumé, avancement, et le téléchargement au bout de la ligne. */
@Composable
private fun EpisodeRow(
    ep: EpisodeEntity,
    progress: WatchProgressEntity?,
    quality: String?,
    tmdb: TmdbEpisode? = null,
    isFresh: Boolean = false,
    download: DownloadEntity?,
    focus: FocusRequester,
    onFocus: () -> Unit,
    onLeft: () -> Unit,
    onDownload: () -> Unit,
    onToggleWatched: () -> Unit,
    onPlay: () -> Unit,
) {
    val poster = ep.poster.ifBlank { tmdb?.stillUrl.orEmpty() }
    val plot = ep.plot.ifBlank { tmdb?.overview.orEmpty() }
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.02f) else 1f, label = "episode")
    val shape = RoundedCornerShape(dpx(16))
    val watched = progress != null && isWatched(progress.positionMs, progress.durationMs)
    val fraction = progress?.takeIf { it.durationMs > 0 && !watched }?.let { it.positionMs.toFloat() / it.durationMs }
    // Écart supérieur aux 2 % d'agrandissement de la ligne sélectionnée : elle ne mord pas sur le bouton.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(32))) {
        Row(
            Modifier.weight(1f)
                .graphicsLayer { scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, 0.5f) }
                .focusGlow(f, shape).clip(shape).background(Color(if (f) 0x1FFFFFFF else 0x08FFFFFF))
                .focusRequester(focus)
                .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onLeft(); true } else false
                }
                .clickable(onClick = onPlay)
                .padding(dpx(12)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dpx(18)),
        ) {
            Box(Modifier.width(dpx(300)).height(dpx(169)).clip(RoundedCornerShape(dpx(10))).background(Surf2), contentAlignment = Alignment.Center) {
                Text("${ep.episode}", color = Tok.TextLow, fontSize = spx(44), fontWeight = FontWeight.Bold, maxLines = 1)
                if (poster.isNotBlank()) NetImage(poster, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (f) {
                    Box(Modifier.size(dpx(58)).clip(CircleShape).background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(dpx(34)), tint = Color.White)
                    }
                }
                if (isFresh && !watched) {
                    Text(
                        "NOUVEAU", color = Color.Black, fontSize = spx(13), fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.align(Alignment.TopStart).padding(dpx(8)).clip(RoundedCornerShape(50))
                            .background(Accent1).padding(horizontal = dpx(10), vertical = dpx(3)),
                    )
                }
                if (watched) {
                    Text(
                        "Vu", color = Color.White, fontSize = spx(14), fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.align(Alignment.TopEnd).padding(dpx(8)).clip(RoundedCornerShape(50))
                            .background(Color(0xCC000000)).padding(horizontal = dpx(10), vertical = dpx(3)),
                    )
                }
                if (fraction != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(dpx(5)).background(Color(0x55000000))) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Accent1))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dpx(4))) {
                Text(episodeTitle(ep.episode, ep.title), color = Tok.TextHi, fontSize = spx(if (f) 26 else 24), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val airDate = tmdb?.airDate?.takeIf { it.isNotBlank() }?.let { Fmt.isoDate(it) }
                val meta = listOfNotNull(airDate, Fmt.minutes(ep.durationSecs).ifBlank { null }, quality).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = Tok.TextLow, fontSize = spx(16), maxLines = 1)
                if (plot.isNotBlank()) Text(plot, color = Tok.TextMid, fontSize = spx(18), lineHeight = spx(25), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(dpx(14)), verticalAlignment = Alignment.CenterVertically) {
            EpisodeWatched(watched, onToggleWatched)
            EpisodeDownload(download, onDownload)
        }
    }
}

/** Épisode annoncé sur TMDB mais pas encore présent chez le fournisseur. */
@Composable
private fun UpcomingTvEpisodeRow(ep: TmdbEpisode, focus: FocusRequester, onLeft: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(16))
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { alpha = if (f) 0.85f else 0.58f }
            .focusGlow(f, shape).clip(shape).background(Color(0x06FFFFFF))
            .focusRequester(focus)
            .onFocusChanged { f = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onLeft(); true } else false
            }
            .focusable()
            .padding(dpx(12)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dpx(18)),
    ) {
        Box(Modifier.width(dpx(300)).height(dpx(169)).clip(RoundedCornerShape(dpx(10))).background(Surf2), contentAlignment = Alignment.Center) {
            Text("${ep.number}", color = Tok.TextLow, fontSize = spx(44), fontWeight = FontWeight.Bold, maxLines = 1)
            if (!ep.stillUrl.isNullOrBlank()) NetImage(ep.stillUrl, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text(
                "À venir · ${Fmt.isoDate(ep.airDate)}", color = Color(0xFFBFDBFE), fontSize = spx(13), fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.align(Alignment.TopStart).padding(dpx(8)).clip(RoundedCornerShape(50))
                    .background(Color(0xCC1E3A8A)).padding(horizontal = dpx(10), vertical = dpx(3)),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dpx(4))) {
            Text(episodeTitle(ep.number, ep.title), color = Tok.TextHi, fontSize = spx(22), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ep.overview.isNotBlank()) Text(ep.overview, color = Tok.TextMid, fontSize = spx(17), lineHeight = spx(23), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Bouton « Vu » d'un épisode, atteint avec ▶ depuis la ligne : il s'ouvre sur son libellé quand on le sélectionne. */
@Composable
private fun EpisodeWatched(watched: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val tint = if (f) Tok.FocusInk else if (watched) Accent1 else Tok.TextMid
    Row(
        Modifier.height(dpx(64)).clip(RoundedCornerShape(50)).background(if (f) Tok.FocusFill else Color(0x14FFFFFF))
            .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick)
            .padding(horizontal = dpx(17)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dpx(10)),
    ) {
        Icon(
            if (watched) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline,
            if (watched) "Vu, valider pour retirer" else "Marquer comme vu", Modifier.size(dpx(30)), tint = tint,
        )
        if (f) Text(watchedActionLabel(watched), color = tint, fontSize = spx(18), fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Bouton rond de téléchargement d'un épisode, atteint avec ▶ depuis la ligne. */
@Composable
private fun EpisodeDownload(download: DownloadEntity?, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val tint = if (f) Tok.FocusInk else Tok.TextMid
    Box(
        Modifier.size(dpx(64)).clip(CircleShape).background(if (f) Tok.FocusFill else Color(0x14FFFFFF))
            .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val iconSize = Modifier.size(dpx(30))
        when {
            download == null -> Icon(Icons.Default.ArrowDownward, "Télécharger", iconSize, tint = tint)
            download.isDownloading -> {
                CircularProgressIndicator(progress = { download.progress }, Modifier.size(dpx(48)), color = if (f) Tok.FocusInk else Accent1, strokeWidth = 2.dp)
                Icon(Icons.Default.Pause, "Mettre en pause", Modifier.size(dpx(20)), tint = tint)
            }
            download.isQueued -> CircularProgressIndicator(Modifier.size(dpx(36)), color = tint, strokeWidth = 2.dp)
            download.isPaused -> Icon(Icons.Default.PlayArrow, "Reprendre le téléchargement", iconSize, tint = if (f) tint else Color(0xFFFFB74D))
            download.isCompleted -> Icon(Icons.Default.Check, "Téléchargé, valider pour supprimer", iconSize, tint = if (f) tint else Color(0xFF4CAF50))
            else -> Icon(Icons.Default.Refresh, "Réessayer le téléchargement", iconSize, tint = if (f) tint else Color(0xFFFF8A80))
        }
    }
}
