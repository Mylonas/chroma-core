package com.mikmy.chromacore

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tiny procedural sound engine. Every sound is synthesised at startup into a
 * PCM buffer, and a single background thread software-mixes the active voices
 * into one streaming AudioTrack. No audio assets, no SoundPool file juggling.
 *
 * Everything is wrapped defensively: if audio is unavailable the game must
 * keep running silently rather than crash.
 */
class Sfx {

    companion object {
        private const val SR = 22050
        private const val CHUNK = 512

        // A minor pentatonic ladder — the combo climbs it, which is most of the
        // reason chaining blocks feels good.
        private val LADDER = floatArrayOf(
            440f, 523f, 587f, 659f, 784f, 880f, 1046f, 1174f, 1318f, 1568f, 1760f, 2093f
        )
    }

    private class Voice(val data: ShortArray, var pos: Int, val gain: Float)

    private val bank = HashMap<String, ShortArray>()
    private val voices = ArrayList<Voice>(24)
    private val lock = Any()

    @Volatile private var running = false
    @Volatile var muted = false
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        buildBank()
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, CHUNK * 4)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            track = t
            running = true
            thread = Thread { mixLoop(t) }.also { it.isDaemon = true; it.start() }
        } catch (e: Throwable) {
            running = false
            track = null
        }
    }

    fun stop() {
        running = false
        thread?.let { runCatching { it.join(300) } }
        thread = null
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
        synchronized(lock) { voices.clear() }
    }

    fun play(name: String, gain: Float = 1f) {
        if (!running || muted) return
        val data = bank[name] ?: return
        synchronized(lock) {
            if (voices.size > 20) voices.removeAt(0)
            voices.add(Voice(data, 0, gain))
        }
    }

    /** Combo blip: pitch climbs the ladder as the streak grows. */
    fun playHit(step: Int, gain: Float = 1f) =
        play("hit${step.coerceIn(0, LADDER.size - 1)}", gain)

    private fun mixLoop(t: AudioTrack) {
        val mix = FloatArray(CHUNK)
        val out = ShortArray(CHUNK)
        while (running) {
            java.util.Arrays.fill(mix, 0f)
            synchronized(lock) {
                var i = 0
                while (i < voices.size) {
                    val v = voices[i]
                    val d = v.data
                    var p = v.pos
                    var k = 0
                    while (k < CHUNK && p < d.size) {
                        mix[k] += d[p] * v.gain
                        k++; p++
                    }
                    v.pos = p
                    if (p >= d.size) voices.removeAt(i) else i++
                }
            }
            for (k in 0 until CHUNK) {
                val s = mix[k]
                out[k] = when {
                    s > 32000f -> 32000
                    s < -32000f -> -32000
                    else -> s.toInt().toShort()
                }
            }
            try {
                t.write(out, 0, CHUNK)
            } catch (e: Throwable) {
                running = false
            }
        }
    }

    // ---------------------------------------------------------------- synth

    private fun buildBank() {
        if (bank.isNotEmpty()) return

        for (i in LADDER.indices) {
            val f = LADDER[i]
            bank["hit$i"] = mix(
                tone(0.10f, f, f * 1.5f, 0.30f, WAVE_SINE, 12f),
                tone(0.06f, f * 2f, f * 2.6f, 0.10f, WAVE_SQUARE, 26f)
            )
        }

        bank["perfect"] = mix(
            tone(0.22f, 1046f, 1568f, 0.26f, WAVE_SINE, 9f),
            tone(0.22f, 1568f, 2093f, 0.16f, WAVE_SINE, 11f),
            tone(0.05f, 3000f, 4200f, 0.10f, WAVE_SQUARE, 40f)
        )

        bank["gold"] = mix(
            tone(0.11f, 880f, 880f, 0.22f, WAVE_SINE, 14f),
            delay(tone(0.11f, 1318f, 1318f, 0.22f, WAVE_SINE, 14f), 0.07f),
            delay(tone(0.30f, 1760f, 1760f, 0.24f, WAVE_SINE, 8f), 0.14f)
        )

        bank["overdrive"] = mix(
            tone(0.09f, 330f, 330f, 0.22f, WAVE_SAW, 12f),
            delay(tone(0.09f, 440f, 440f, 0.22f, WAVE_SAW, 12f), 0.07f),
            delay(tone(0.09f, 554f, 554f, 0.22f, WAVE_SAW, 12f), 0.14f),
            delay(tone(0.45f, 660f, 880f, 0.26f, WAVE_SAW, 5f), 0.21f)
        )

        bank["damage"] = mix(
            tone(0.28f, 260f, 60f, 0.34f, WAVE_SAW, 9f),
            tone(0.10f, 120f, 40f, 0.30f, WAVE_NOISE, 18f)
        )

        bank["gameover"] = mix(
            tone(0.80f, 330f, 55f, 0.30f, WAVE_SAW, 3.2f),
            tone(0.80f, 165f, 41f, 0.22f, WAVE_SINE, 3.2f)
        )

        bank["flip"] = tone(0.035f, 1200f, 900f, 0.14f, WAVE_SQUARE, 55f)
        bank["start"] = mix(
            tone(0.10f, 523f, 523f, 0.20f, WAVE_SINE, 14f),
            delay(tone(0.10f, 784f, 784f, 0.20f, WAVE_SINE, 14f), 0.08f),
            delay(tone(0.36f, 1046f, 1046f, 0.24f, WAVE_SINE, 7f), 0.16f)
        )
        bank["wave"] = mix(
            tone(0.16f, 200f, 700f, 0.18f, WAVE_SQUARE, 8f)
        )
    }

    private val WAVE_SINE = 0
    private val WAVE_SQUARE = 1
    private val WAVE_SAW = 2
    private val WAVE_NOISE = 3

    /** One enveloped oscillator sweep from [f0] to [f1]. */
    private fun tone(
        dur: Float,
        f0: Float,
        f1: Float,
        vol: Float,
        wave: Int,
        decay: Float
    ): ShortArray {
        val n = (dur * SR).toInt().coerceAtLeast(1)
        val out = ShortArray(n)
        var phase = 0.0
        val attack = (SR * 0.004f).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val t = i.toFloat() / n
            val f = f0 + (f1 - f0) * t
            phase += 2.0 * PI * f / SR
            if (phase > 2 * PI) phase -= 2 * PI
            val s = when (wave) {
                WAVE_SQUARE -> if (sin(phase) >= 0) 1f else -1f
                WAVE_SAW -> (((phase / (2 * PI)) * 2.0) - 1.0).toFloat()
                WAVE_NOISE -> Random.nextFloat() * 2f - 1f
                else -> sin(phase).toFloat()
            }
            var env = exp(-decay * t)
            if (i < attack) env *= i.toFloat() / attack
            out[i] = (s * env * vol * 32767f).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun delay(a: ShortArray, sec: Float): ShortArray {
        val pad = (sec * SR).toInt()
        val out = ShortArray(a.size + pad)
        System.arraycopy(a, 0, out, pad, a.size)
        return out
    }

    private fun mix(vararg parts: ShortArray): ShortArray {
        val n = parts.maxOf { it.size }
        val acc = FloatArray(n)
        for (p in parts) for (i in p.indices) acc[i] += p[i].toFloat()
        val out = ShortArray(n)
        for (i in 0 until n) out[i] = acc[i].coerceIn(-32767f, 32767f).toInt().toShort()
        return out
    }
}
