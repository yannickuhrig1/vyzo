package fr.yannickuhrig.vyzo.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.yannickuhrig.vyzo.R
import fr.yannickuhrig.vyzo.VyzoApp
import fr.yannickuhrig.vyzo.data.link.LauncherItem
import fr.yannickuhrig.vyzo.data.link.LauncherKind
import fr.yannickuhrig.vyzo.data.link.LauncherRows
import fr.yannickuhrig.vyzo.data.link.launcherRows
import fr.yannickuhrig.vyzo.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Programme déjà publié sur l'écran d'accueil. */
data class PublishedProgram(val rowId: Long, val key: String, val fingerprint: Long)

data class ProgramPlan(
    val delete: List<Long>,
    val insert: List<IndexedValue<LauncherItem>>,
    val update: List<Pair<Long, IndexedValue<LauncherItem>>>,
)

/** Empreinte d'une carte à sa place dans la rangée : la republier ne sert à rien si elle n'a pas changé. */
fun LauncherItem.fingerprint(rank: Int): Long =
    listOf(rank, kind, title, description, image, link.uri, positionMs, durationMs).hashCode().toLong()

/** Ce qu'il faut changer pour que la rangée publiée corresponde à [wanted]. */
fun planPrograms(published: List<PublishedProgram>, wanted: List<LauncherItem>): ProgramPlan {
    val keep = published.groupBy { it.key }.mapValues { it.value.first() }
    val wantedKeys = wanted.map { it.uid }.toSet()
    val delete = published.filter { it.key !in wantedKeys || keep[it.key] !== it }.map { it.rowId }
    val insert = mutableListOf<IndexedValue<LauncherItem>>()
    val update = mutableListOf<Pair<Long, IndexedValue<LauncherItem>>>()
    wanted.withIndex().forEach { entry ->
        val current = keep[entry.value.uid]
        when {
            current == null -> insert += entry
            current.fingerprint != entry.value.fingerprint(entry.index) -> update += current.rowId to entry
        }
    }
    return ProgramPlan(delete, insert, update)
}

/** Publie les rangées « Reprendre » et « Favoris » sur l'écran d'accueil d'Android TV. */
@RequiresApi(Build.VERSION_CODES.O)
class HomeChannels(private val context: Context) {
    private val helper = PreviewChannelHelper(context)

    private fun ownChannels() = helper.allChannels.filter { it.internalProviderId in IDS }

    /** Renvoie un résumé pour le journal, sans aucun titre. */
    fun publish(rows: LauncherRows): String {
        syncRow(channelId(ID_RESUME, "Reprendre", default = true), rows.resume)
        syncRow(channelId(ID_FAVORITES, "Favoris", default = false), rows.favorites)
        return "Rangées publiées : Reprendre ${rows.resume.size}, Favoris ${rows.favorites.size}, visibles ${ownChannels().count { it.isBrowsable }}"
    }

    fun removeAll() = ownChannels().forEach { helper.deletePreviewChannel(it.id) }

    /** Rangée Favoris à proposer d'ajouter à l'écran d'accueil, si elle a du contenu et n'y figure pas. */
    private fun favoritesToOffer(): Long? =
        ownChannels().firstOrNull { it.internalProviderId == ID_FAVORITES && !it.isBrowsable }
            ?.id?.takeIf { published(it).isNotEmpty() }

    private fun channelId(id: String, title: String, default: Boolean): Long {
        ownChannels().firstOrNull { it.internalProviderId == id }?.let { return it.id }
        val appIntent = context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        val builder = PreviewChannel.Builder()
            .setDisplayName(title)
            .setAppLinkIntent(appIntent)
            .setInternalProviderId(id)
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.let { builder.setLogo(it.toBitmap(LOGO_PX, LOGO_PX)) }
        return if (default) helper.publishDefaultChannel(builder.build()) else helper.publishChannel(builder.build())
    }

    private fun published(channelId: Long): List<PublishedProgram> {
        val projection = arrayOf(
            TvContractCompat.PreviewPrograms._ID,
            TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_FLAG1,
        )
        val uri = TvContractCompat.buildPreviewProgramsUriForChannel(channelId)
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(PublishedProgram(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getLong(2)))
            }
        }.orEmpty()
    }

    private fun syncRow(channelId: Long, items: List<LauncherItem>) {
        val plan = planPrograms(published(channelId), items)
        plan.delete.forEach { helper.deletePreviewProgram(it) }
        plan.insert.forEach { (rank, item) -> helper.publishPreviewProgram(program(channelId, item, rank, items.size)) }
        plan.update.forEach { (rowId, entry) -> helper.updatePreviewProgram(rowId, program(channelId, entry.value, entry.index, items.size)) }
    }

    private fun program(channelId: Long, item: LauncherItem, rank: Int, total: Int): PreviewProgram {
        // Explicite : sur une TV où l'appli et sa version de test cohabitent, le lien doit rouvrir celle qui l'a publié.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.link.uri)).setPackage(context.packageName)
        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(
                when (item.kind) {
                    LauncherKind.CHANNEL -> TvContractCompat.PreviewPrograms.TYPE_CHANNEL
                    LauncherKind.MOVIE -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
                    LauncherKind.SERIES -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
                },
            )
            .setTitle(item.title)
            .setDescription(item.description.take(MAX_DESCRIPTION))
            .setPosterArtUri(Uri.parse(item.image))
            .setPosterArtAspectRatio(
                if (item.kind == LauncherKind.CHANNEL) TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1
                else TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3,
            )
            .setIntent(intent)
            .setInternalProviderId(item.uid)
            .setInternalProviderFlag1(item.fingerprint(rank))
            .setWeight(total - rank)
        if (item.durationMs > 0) {
            builder.setDurationMillis(item.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            builder.setLastPlaybackPositionMillis(item.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        return builder.build()
    }

    companion object {
        private const val ID_RESUME = "vyzo-resume"
        private const val ID_FAVORITES = "vyzo-favorites"
        private val IDS = setOf(ID_RESUME, ID_FAVORITES)
        private const val LOGO_PX = 256
        private const val MAX_DESCRIPTION = 300
        private const val PREFS = "home_channels"
        private const val KEY_ASKED = "favorites_offered"
        private const val REQUEST_BROWSABLE = 4711

        fun supported(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            val pm = context.packageManager
            return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
                !pm.hasSystemFeature("amazon.hardware.fire_tv") &&
                pm.resolveContentProvider(TvContract.AUTHORITY, 0) != null
        }

        /** Android n'affiche d'office que la première rangée d'une appli : on propose une seule fois d'ajouter la seconde. */
        fun offerFavoritesOnce(activity: ComponentActivity) {
            if (!supported(activity)) return
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_ASKED, false)) return
            activity.lifecycleScope.launch {
                val channelId = withContext(Dispatchers.IO) {
                    runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) HomeChannels(activity).favoritesToOffer() else null }.getOrNull()
                } ?: return@launch
                prefs.edit { putBoolean(KEY_ASKED, true) }
                runCatching {
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(
                        Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE).putExtra(TvContractCompat.EXTRA_CHANNEL_ID, channelId),
                        REQUEST_BROWSABLE,
                    )
                }
            }
        }
    }
}

/** Remet à jour les rangées de l'écran d'accueil, sans jamais gêner l'appli si le système les refuse. */
class HomeChannelsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !HomeChannels.supported(applicationContext)) return Result.success()
        val container = (applicationContext as VyzoApp).container
        val channels = HomeChannels(applicationContext)
        val enabled = container.settings.get().tvHomeRows
        val rows = if (enabled) withTimeoutOrNull(LOAD_TIMEOUT_MS) { container.launcherRows() } ?: return Result.success() else null
        withContext(Dispatchers.IO) {
            runCatching { if (rows != null) Log.i(TAG, channels.publish(rows)) else channels.removeAll() }
                .onFailure { Log.w(TAG, "Rangées de l'écran d'accueil non publiées", it) }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "HomeChannels"
        private const val PERIODIC = "vyzo-home-channels"
        private const val ONCE = "vyzo-home-channels-now"
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val PERIOD_HOURS = 6L
        private const val REFRESH_DELAY_S = 3L

        fun schedule(context: Context) {
            val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            val request = PeriodicWorkRequestBuilder<HomeChannelsWorker>(PERIOD_HOURS, TimeUnit.HOURS).build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun refreshSoon(context: Context) {
            val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            val request = OneTimeWorkRequestBuilder<HomeChannelsWorker>().setInitialDelay(REFRESH_DELAY_S, TimeUnit.SECONDS).build()
            wm.enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
