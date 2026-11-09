package fr.yannickuhrig.vyzo.player

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Égalise le volume entre les chaînes : un gain lent ramène le niveau moyen vers une même cible.
 * Il baisse vite (une publicité qui hurle) et remonte doucement (pour ne pas pomper dans les silences).
 * Les canaux partagent le même gain, pour garder l'image stéréo.
 */
class AudioLeveler(private val sampleRate: Int, private val channels: Int) {
    private var power = -1.0
    private var gain = 1.0

    /** Traite [count] échantillons 16 bits entrelacés, sur place. */
    fun process(samples: ShortArray, count: Int) {
        val blockSamples = max(1, sampleRate * channels / BLOCKS_PER_SECOND)
        var offset = 0
        while (offset < count) {
            val size = min(blockSamples, count - offset)
            processBlock(samples, offset, size)
            offset += size
        }
    }

    private fun processBlock(samples: ShortArray, offset: Int, size: Int) {
        var sum = 0.0
        var peak = 0.0
        for (i in offset until offset + size) {
            val v = samples[i] / FULL_SCALE
            sum += v * v
            peak = max(peak, abs(v))
        }
        val seconds = size.toDouble() / (sampleRate * channels)
        val blockPower = sum / size
        // Le silence ne compte pas : sinon le souffle entre deux répliques serait amplifié.
        if (blockPower >= GATE_POWER) {
            power = if (power < 0) blockPower else power + (blockPower - power) * smoothing(seconds, MEASURE_SECONDS)
        }
        val wanted = if (power < 0) 1.0 else (TARGET_RMS / sqrt(power)).coerceIn(MIN_GAIN, MAX_GAIN)
        val time = if (wanted < gain) LOWER_SECONDS else RAISE_SECONDS
        var next = gain + (wanted - gain) * smoothing(seconds, time)
        if (peak * next > PEAK_LIMIT) next = PEAK_LIMIT / peak
        // Rampe sur le bloc : un saut de gain s'entendrait comme un clic.
        for (i in 0 until size) {
            val g = gain + (next - gain) * (i + 1) / size
            val index = offset + i
            samples[index] = (samples[index] * g).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        gain = next
    }

    private fun smoothing(seconds: Double, timeConstant: Double) = 1.0 - exp(-seconds / timeConstant)

    private companion object {
        const val FULL_SCALE = 32768.0
        const val BLOCKS_PER_SECOND = 50
        const val TARGET_RMS = 0.1 // -20 dBFS
        const val GATE_POWER = 3.2e-6 // -55 dBFS
        const val MIN_GAIN = 0.25 // -12 dB
        const val MAX_GAIN = 4.0 // +12 dB
        const val PEAK_LIMIT = 0.98
        const val MEASURE_SECONDS = 1.5
        const val LOWER_SECONDS = 0.15
        const val RAISE_SECONDS = 3.0
    }
}
