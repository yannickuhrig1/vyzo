package fr.yannickuhrig.vyzo.ui.common

import coil3.EventListener
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import fr.yannickuhrig.vyzo.data.net.Http
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Chargeur d'images (logos, affiches) commun à Android et au PC : client HTTP de l'appli,
 * cache mémoire et cache disque de 250 Mo. Les en-têtes de cache des serveurs IPTV sont ignorés.
 * [listener] : suivi des images décodées (PC : mémoire native à rendre, voir NativeMemory).
 */
fun vyzoImageLoader(context: PlatformContext, cacheDir: File, listener: EventListener? = null): ImageLoader =
    ImageLoader.Builder(context)
        .apply { if (listener != null) eventListener(listener) }
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
        .crossfade(true)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache {
            DiskCache.Builder().directory(cacheDir.resolve("images").toOkioPath()).maxSizeBytes(250L * 1024 * 1024).build()
        }
        .build()
