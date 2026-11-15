package fr.yannickuhrig.vyzo.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import fr.yannickuhrig.vyzo.MainActivity

/**
 * Réglages du SDK Cast (déclarés dans le manifeste de l'appli mobile) : récepteur standard de Google, donc rien à
 * enregistrer chez Google ; notification avec les commandes pendant le Cast, qui ramène dans Vyzo.
 */
class VyzoCastOptions : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val notification = NotificationOptions.Builder()
            .setTargetActivityClassName(MainActivity::class.java.name)
            .build()
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(notification)
            .setMediaSessionEnabled(true)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(media)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
