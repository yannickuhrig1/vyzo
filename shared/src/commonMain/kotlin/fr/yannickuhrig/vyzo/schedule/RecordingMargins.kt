package fr.yannickuhrig.vyzo.schedule

import fr.yannickuhrig.vyzo.data.db.RecordingEntity

/** Marge d'enregistrement la plus large, en ms. */
fun recordingMarginMs(beforeMin: Int, afterMin: Int): Long = maxOf(beforeMin, afterMin) * 60_000L

/**
 * L'enregistrement couvre-t-il vraiment l'émission [start, stop) ? Ses marges débordent sur les émissions voisines
 * sans les enregistrer : il faut un chevauchement plus long que la marge (ou que la moitié d'une émission très courte).
 */
fun RecordingEntity.covers(start: Long, stop: Long, marginMs: Long): Boolean =
    minOf(this.stop, stop) - maxOf(this.start, start) > minOf(marginMs, (stop - start) / 2)
