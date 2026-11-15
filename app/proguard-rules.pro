# Vyzo
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# Google Cast : le SDK charge ses réglages par le nom donné dans le manifeste (appli mobile).
-keep class fr.yannickuhrig.vyzo.cast.VyzoCastOptions { *; }
