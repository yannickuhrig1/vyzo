package fr.yannickuhrig.vyzo.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.yannickuhrig.vyzo.VyzoApp
import java.util.concurrent.TimeUnit

/** Mise à jour périodique des playlists et du guide en arrière-plan. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as VyzoApp).container
        container.sync.syncAll()
        runCatching { container.newEpisodes.check() }
        return Result.success()
    }

    companion object {
        private const val NAME = "vyzo-sync"

        fun schedule(context: Context, hours: Int) {
            val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            if (hours <= 0) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<SyncWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(hours.toLong(), TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
