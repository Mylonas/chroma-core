package com.mikmy.chromacore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * CHROMA CORE
 *
 * Orbs fall toward your core from every direction. You spin a two-tone shield
 * around it and tap to swap which side is which colour. Block an orb with the
 * matching colour and the combo climbs; block it dead-centre and it's a
 * PERFECT. Miss it, or catch it on the wrong colour, and the core takes a hit.
 * Three hits and the run is over — and the retry tap is always one tap away.
 */
class Game(ctx: Context, private val sfx: Sfx) {

    // ------------------------------------------------------------- palette
    private val colA = 0xFF00E5FF.toInt()   // cyan
    private val colB = 0xFFFF2E88.toInt()   // magenta
    private val colWild = 0xFFF2F6FF.toInt()
    private val colGold = 0xFFFFC53D.toInt()
    private val colBg = 0xFF05060E.toInt()
    private val colDim = 0xFF1B2138.toInt()

    // ------------------------------------------------------------- tuning
    private val maxHp = 3
    private val perfectWindow = (13f * PI / 180f).toFloat()
    private val comboForOverdrive = 15
    private val overdriveTime = 6f

    // ------------------------------------------------------------- state
    private enum class Phase { TITLE, PLAY, OVER }

    private var phase = Phase.TITLE

    private var w = 1f
    private var h = 1f
    private var cx = 0f
    private var cy = 0f
    private var minDim = 1f
    private var coreR = 1f
    private var shieldR = 1f
    private var orbR = 1f
    private var shieldW = 1f
    private var spawnR = 1f

    private var shieldAng = -PI.toFloat() / 2f
    private var flipped = false
    private var time = 0f          // seconds inside the current run
    private var clock = 0f         // never resets, drives idle animation
    private var score = 0
    private var combo = 0
    private var bestComboRun = 0
    private var hp = maxHp
    private var overdrive = 0f
    private var spawnTimer = 1.2f
    private var nextWaveCall = 25f
    private var shake = 0f
    private var flash = 0f
    private var goldFlash = 0f
    private var hitStop = 0f
    private var overTimer = 0f
    private var newBest = false
    private var shieldPulse = 0f
    private var corePulse = 0f

    private val prefs = ctx.getSharedPreferences("chroma_core", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)
    private var bestCombo = prefs.getInt("bestCombo", 0)
    private var runs = prefs.getInt("runs", 0)

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    } catch (e: Throwable) {
        null
    }

    // ------------------------------------------------------------- entities
    private class Orb(
        var ang: Float,
        var r: Float,
        var speed: Float,
        var col: Int,
        var kind: Int
    ) {
        var prevR = r
        var dead = false
        var split = false
        var spin = Random.nextFloat() * 6.28f
    }

    private class Particle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var life: Float, var maxLife: Float,
        var size: Float, var col: Int,
        var streak: Boolean
    )

    private class FloatText(
        var x: Float, var y: Float,
        var text: String, var col: Int,
        var life: Float, var maxLife: Float,
        var size: Float
    )

    private companion object {
        const val NORMAL = 0
        const val WILD = 1
        const val GOLD = 2
        const val SPLITTER = 3
    }

    private val orbs = ArrayList<Orb>(40)
    private val parts = ArrayList<Particle>(400)
    private val texts = ArrayList<FloatText>(16)

    private var starX = FloatArray(0)
    private var starY = FloatArray(0)
    private var starP = FloatArray(0)
    private var starS = FloatArray(0)

    // ------------------------------------------------------------- paint
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()
    private val muteRect = RectF()
    private val slashPath = Path()
    private val fontBold: Typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    private val fontCond: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    // ------------------------------------------------------------- input
    private var touchX = 0f
    private var touchDrag = 0f
    private var touching = false
    private var downX = 0f
    private var downY = 0f

    fun resize(width: Int, height: Int) {
        w = width.toFloat()
        h = height.toFloat()
        minDim = min(w, h)
        cx = w / 2f
        cy = h * 0.55f
        coreR = minDim * 0.072f
        shieldR = minDim * 0.30f
        shieldW = minDim * 0.024f
        orbR = minDim * 0.028f
        spawnR = hypot(max(cx, w - cx), max(cy, h - cy)) + orbR * 3f

        val n = 80
        starX = FloatArray(n); starY = FloatArray(n)
        starP = FloatArray(n); starS = FloatArray(n)
        for (i in 0 until n) {
            starX[i] = Random.nextFloat() * w
            starY[i] = Random.nextFloat() * h
            starP[i] = Random.nextFloat() * 6.28f
            starS[i] = minDim * (0.0015f + Random.nextFloat() * 0.0035f)
        }

        val bs = minDim * 0.055f
        muteRect.set(w - bs * 2.4f, h * 0.045f, w - bs * 0.6f, h * 0.045f + bs * 1.8f)
    }

    // ============================================================== UPDATE

    fun update(rawDt: Float) {
        var dt = rawDt.coerceIn(0f, 0.05f)
        clock += dt
        if (hitStop > 0f) {
            hitStop -= dt
            dt *= 0.28f
        }

        shake *= (1f - min(1f, dt * 9f))
        flash = max(0f, flash - dt * 2.2f)
        goldFlash = max(0f, goldFlash - dt * 1.8f)
        shieldPulse = max(0f, shieldPulse - dt * 4.5f)
        corePulse = max(0f, corePulse - dt * 3f)

        when (phase) {
            Phase.TITLE -> shieldAng += dt * 0.55f
            Phase.PLAY -> updatePlay(dt)
            Phase.OVER -> overTimer += dt
        }

        updateParticles(dt)
        updateTexts(dt)
    }

    private fun updatePlay(dt: Float) {
        time += dt
        if (overdrive > 0f) {
            overdrive -= dt
            if (overdrive <= 0f) {
                overdrive = 0f
                pushText(cx, cy - shieldR * 1.5f, "OVERDRIVE OVER", colDim, 0.9f, minDim * 0.045f)
            }
        }

        val d = min(1f, time / 95f)
        val spawnEvery = max(0.28f, 1.15f - d * 0.82f - max(0f, time - 95f) * 0.0016f)
        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnBurst(d)
            spawnTimer = spawnEvery * (0.82f + Random.nextFloat() * 0.36f)
        }

        if (time >= nextWaveCall) {
            nextWaveCall += 25f
            pushText(cx, cy - shieldR * 1.5f, "SPEED UP", colWild, 1.1f, minDim * 0.055f)
            sfx.play("wave", 0.8f)
        }

        var i = 0
        while (i < orbs.size) {
            val o = orbs[i]
            o.prevR = o.r
            o.r -= o.speed * dt
            o.spin += dt * 3f

            if (o.kind == SPLITTER && !o.split && o.r <= shieldR * 2.1f) {
                o.split = true
                o.kind = NORMAL
                o.col = if (Random.nextBoolean()) colA else colB
                val sib = Orb(
                    o.ang + 0.42f, o.r + orbR * 0.6f, o.speed * 0.94f,
                    if (Random.nextBoolean()) colA else colB, NORMAL
                )
                o.ang -= 0.42f
                orbs.add(sib)
                burst(polarX(o.ang, o.r), polarY(o.ang, o.r), colWild, 12, minDim * 0.22f)
                sfx.play("flip", 0.9f)
            }

            if (o.prevR > shieldR && o.r <= shieldR) resolve(o)

            if (!o.dead && o.r <= coreR + orbR * 0.35f) {
                o.dead = true
                burst(polarX(o.ang, o.r), polarY(o.ang, o.r), o.col, 22, minDim * 0.45f)
                damage()
            }

            if (o.dead) orbs.removeAt(i) else i++
        }
    }

    private fun spawnBurst(d: Float) {
        var n = 1
        if (d > 0.32f && Random.nextFloat() < 0.26f) n = 2
        if (d > 0.62f && Random.nextFloat() < 0.16f) n = 3
        val base = Random.nextFloat() * 6.2832f
        for (k in 0 until n) {
            val ang = base + k * (1.6f + Random.nextFloat() * 1.6f)
            spawnOrb(ang, d, k * 0.22f)
        }
    }

    private fun spawnOrb(ang: Float, d: Float, stagger: Float) {
        val roll = Random.nextFloat()
        val kind = when {
            roll < (if (hp < maxHp) 0.035f else 0.014f) -> GOLD
            d > 0.45f && roll < 0.16f -> SPLITTER
            d > 0.22f && roll < 0.28f -> WILD
            else -> NORMAL
        }
        val col = when (kind) {
            GOLD -> colGold
            WILD -> colWild
            else -> if (Random.nextBoolean()) colA else colB
        }
        val speed = minDim * (0.165f + d * 0.30f) * (0.9f + Random.nextFloat() * 0.22f)
        orbs.add(Orb(ang, spawnR + stagger * minDim, speed, col, kind))
    }

    /** The orb just crossed the shield radius — decide what happens. */
    private fun resolve(o: Orb) {
        val halfSpan = shieldHalfSpan()
        val diff = angDiff(o.ang, shieldAng)
        val anyColour = o.kind == WILD || o.kind == GOLD
        val verdict = Rules.outcome(
            diff, halfSpan, o.col, anyColour, overdrive > 0f, flipped, colA, colB
        )
        if (verdict == Rules.OUTCOME_MISS) return  // sails past, heading for the core

        val sideCol = sideColor(Rules.onRightHalf(diff))

        if (verdict == Rules.OUTCOME_BLOCK) block(o, abs(diff), halfSpan, sideCol)
        else {
            o.dead = true
            burst(polarX(o.ang, o.r), polarY(o.ang, o.r), o.col, 20, minDim * 0.40f)
            pushText(polarX(o.ang, shieldR * 1.28f), polarY(o.ang, shieldR * 1.28f),
                "WRONG COLOUR", o.col, 0.9f, minDim * 0.035f)
            damage()
        }
    }

    private fun block(o: Orb, absDiff: Float, halfSpan: Float, sideCol: Int) {
        o.dead = true
        combo++
        bestComboRun = max(bestComboRun, combo)

        val perfect = Rules.isPerfect(absDiff, halfSpan, perfectWindow)
        val base = if (o.kind == GOLD) 100 else 10
        val gain = Rules.points(base, combo, perfect, overdrive > 0f)
        score += gain

        val ox = polarX(o.ang, shieldR)
        val oy = polarY(o.ang, shieldR)
        val fx = polarX(o.ang, shieldR * 1.26f)
        val fy = polarY(o.ang, shieldR * 1.26f)

        shieldPulse = 1f
        sfx.playHit(min(11, combo / 2), 0.9f)

        when {
            o.kind == GOLD -> {
                if (hp < maxHp) {
                    hp++
                    pushText(fx, fy, "+1 CORE", colGold, 1.3f, minDim * 0.05f)
                } else {
                    pushText(fx, fy, "+$gain", colGold, 1.1f, minDim * 0.05f)
                }
                goldFlash = 1f
                corePulse = 1f
                burst(ox, oy, colGold, 40, minDim * 0.8f)
                sfx.play("gold", 1f)
                tap(18)
            }
            perfect -> {
                pushText(fx, fy, "PERFECT +$gain", colWild, 1.0f, minDim * 0.044f)
                burst(ox, oy, colWild, 26, minDim * 0.7f)
                burst(ox, oy, sideCol, 14, minDim * 0.45f)
                sfx.play("perfect", 0.6f)
                hitStop = 0.055f
                tap(10)
            }
            else -> {
                pushText(fx, fy, "+$gain", sideCol, 0.75f, minDim * 0.036f)
                burst(ox, oy, sideCol, 14, minDim * 0.42f)
            }
        }

        if (combo > 0 && combo % comboForOverdrive == 0) {
            overdrive = overdriveTime
            pushText(cx, cy - shieldR * 1.5f, "OVERDRIVE", colGold, 1.4f, minDim * 0.07f)
            sfx.play("overdrive", 1f)
            burstRing(colGold, 46)
            shake = max(shake, minDim * 0.012f)
            tap(26)
        }
    }

    private fun damage() {
        hp--
        combo = 0
        shake = minDim * 0.022f
        flash = 1f
        corePulse = 1f
        hitStop = 0.05f
        sfx.play("damage", 1f)
        tap(34)
        if (hp <= 0) gameOver()
    }

    private fun gameOver() {
        hp = 0
        phase = Phase.OVER
        overTimer = 0f
        newBest = score > best
        runs++
        if (score > best) best = score
        if (bestComboRun > bestCombo) bestCombo = bestComboRun
        prefs.edit()
            .putInt("best", best)
            .putInt("bestCombo", bestCombo)
            .putInt("runs", runs)
            .apply()
        burstRing(0xFFFF3355.toInt(), 60)
        shake = minDim * 0.03f
        sfx.play("gameover", 1f)
        tap(70)
    }

    private fun startRun() {
        phase = Phase.PLAY
        orbs.clear()
        texts.clear()
        score = 0
        combo = 0
        bestComboRun = 0
        hp = maxHp
        time = 0f
        overdrive = 0f
        spawnTimer = 0.9f
        nextWaveCall = 25f
        flash = 0f
        shieldAng = -PI.toFloat() / 2f
        flipped = false
        sfx.play("start", 0.9f)
        burstRing(colA, 26)
    }

    // ------------------------------------------------------------- helpers

    private fun shieldHalfSpan(): Float =
        if (overdrive > 0f) (76f * PI / 180f).toFloat() else (58f * PI / 180f).toFloat()

    private fun sideColor(rightSide: Boolean): Int =
        if (rightSide != flipped) colB else colA

    private fun polarX(a: Float, r: Float) = cx + cos(a) * r
    private fun polarY(a: Float, r: Float) = cy + sin(a) * r

    private fun angDiff(a: Float, b: Float): Float = Rules.angDiff(a, b)

    private fun tap(ms: Long) {
        val v = vibrator ?: return
        try {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Throwable) {
            // haptics are a nicety, never a crash
        }
    }

    private fun burst(x: Float, y: Float, col: Int, n: Int, power: Float) {
        if (parts.size > 460) return
        for (i in 0 until n) {
            val a = Random.nextFloat() * 6.2832f
            val sp = power * (0.25f + Random.nextFloat() * 0.9f)
            val life = 0.28f + Random.nextFloat() * 0.5f
            parts.add(
                Particle(
                    x, y, cos(a) * sp, sin(a) * sp, life, life,
                    minDim * (0.004f + Random.nextFloat() * 0.007f), col,
                    Random.nextFloat() < 0.35f
                )
            )
        }
    }

    private fun burstRing(col: Int, n: Int) {
        for (i in 0 until n) {
            val a = i.toFloat() / n * 6.2832f
            val sp = minDim * (0.5f + Random.nextFloat() * 0.35f)
            val life = 0.5f + Random.nextFloat() * 0.4f
            parts.add(
                Particle(
                    polarX(a, coreR), polarY(a, coreR),
                    cos(a) * sp, sin(a) * sp, life, life,
                    minDim * 0.005f, col, true
                )
            )
        }
    }

    private fun pushText(x: Float, y: Float, s: String, col: Int, life: Float, size: Float) {
        if (texts.size > 14) texts.removeAt(0)
        texts.add(FloatText(x, y, s, col, life, life, size))
    }

    private fun updateParticles(dt: Float) {
        var i = 0
        while (i < parts.size) {
            val q = parts[i]
            q.life -= dt
            if (q.life <= 0f) {
                parts.removeAt(i)
                continue
            }
            q.x += q.vx * dt
            q.y += q.vy * dt
            val drag = 1f - min(1f, dt * 2.4f)
            q.vx *= drag
            q.vy *= drag
            i++
        }
    }

    private fun updateTexts(dt: Float) {
        var i = 0
        while (i < texts.size) {
            val t = texts[i]
            t.life -= dt
            if (t.life <= 0f) {
                texts.removeAt(i)
                continue
            }
            t.y -= dt * minDim * 0.09f
            i++
        }
    }

    // =============================================================== INPUT

    fun onDown(x: Float, y: Float) {
        touching = true
        touchX = x
        downX = x
        downY = y
        touchDrag = 0f
    }

    fun onMove(x: Float, y: Float) {
        if (!touching) return
        val dx = x - touchX
        touchX = x
        // A finger lifting out of a multi-touch shuffles the pointer indices,
        // which shows up here as an impossible jump. Swallow it.
        if (abs(dx) > w * 0.35f) return
        touchDrag += abs(dx) + abs(y - downY) * 0.15f
        // Horizontal drag anywhere on screen spins the shield: thumb-friendly,
        // and a full sweep of the screen is a bit more than a full rotation.
        shieldAng += dx * (6.2832f / (w * 0.78f))
    }

    fun onUp(x: Float, y: Float) {
        touching = false
        val isTap = touchDrag < minDim * 0.022f && hypot(x - downX, y - downY) < minDim * 0.04f
        if (!isTap) return

        if (muteRect.contains(downX, downY)) {
            sfx.muted = !sfx.muted
            if (!sfx.muted) sfx.play("flip", 1f)
            return
        }

        when (phase) {
            Phase.TITLE -> startRun()
            Phase.PLAY -> {
                flipped = !flipped
                shieldPulse = max(shieldPulse, 0.5f)
                sfx.play("flip", 0.7f)
            }
            Phase.OVER -> if (overTimer > 0.7f) startRun()
        }
    }

    /**
     * A second finger tapping down flips the colours without interrupting the
     * drag — you need both hands' worth of control once the run speeds up.
     */
    fun onSecondFinger() {
        when (phase) {
            Phase.PLAY -> {
                flipped = !flipped
                shieldPulse = max(shieldPulse, 0.5f)
                sfx.play("flip", 0.7f)
            }
            Phase.TITLE -> startRun()
            Phase.OVER -> if (overTimer > 0.7f) startRun()
        }
    }

    /** @return true if the game consumed the back press. */
    fun handleBack(): Boolean {
        return when (phase) {
            Phase.PLAY, Phase.OVER -> {
                phase = Phase.TITLE
                orbs.clear()
                texts.clear()
                true
            }
            Phase.TITLE -> false
        }
    }

    // ================================================================ DRAW

    fun draw(c: Canvas) {
        c.drawColor(colBg)
        val saved = c.save()
        if (shake > 0.4f) {
            c.translate(
                (Random.nextFloat() - 0.5f) * shake * 2f,
                (Random.nextFloat() - 0.5f) * shake * 2f
            )
        }

        drawStars(c)
        drawField(c)
        drawOrbs(c)
        drawShield(c)
        drawCore(c)
        drawParticles(c)
        drawTexts(c)

        c.restoreToCount(saved)

        drawHud(c)
        if (flash > 0.001f) {
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = Color.argb((flash * 90).toInt().coerceIn(0, 255), 255, 40, 70)
            c.drawRect(0f, 0f, w, h, p)
        }
        if (goldFlash > 0.001f) {
            p.style = Paint.Style.FILL
            p.color = Color.argb((goldFlash * 55).toInt().coerceIn(0, 255), 255, 200, 80)
            c.drawRect(0f, 0f, w, h, p)
        }

        when (phase) {
            Phase.TITLE -> drawTitle(c)
            Phase.OVER -> drawGameOver(c)
            else -> {}
        }
        drawMuteButton(c)
    }

    private fun drawStars(c: Canvas) {
        p.style = Paint.Style.FILL
        for (i in starX.indices) {
            val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(clock * 1.6f + starP[i]))
            p.color = Color.argb((110 * tw).toInt(), 150, 190, 255)
            c.drawCircle(starX[i], starY[i], starS[i], p)
        }
    }

    /** Faint orbital guides + the danger ring the orbs are converging on. */
    private fun drawField(c: Canvas) {
        p.style = Paint.Style.STROKE
        p.color = Color.argb(26, 120, 150, 220)
        p.strokeWidth = minDim * 0.0025f
        c.drawCircle(cx, cy, shieldR, p)
        c.drawCircle(cx, cy, shieldR * 1.7f, p)
        c.drawCircle(cx, cy, shieldR * 2.5f, p)

        // rotating tick marks
        p.color = Color.argb(34, 120, 150, 220)
        p.strokeWidth = minDim * 0.004f
        val spin = clock * 0.18f
        for (i in 0 until 24) {
            val a = spin + i * (6.2832f / 24f)
            c.drawLine(
                polarX(a, shieldR * 1.68f), polarY(a, shieldR * 1.68f),
                polarX(a, shieldR * 1.74f), polarY(a, shieldR * 1.74f), p
            )
        }
    }

    private fun drawOrbs(c: Canvas) {
        for (o in orbs) {
            val x = polarX(o.ang, o.r)
            val y = polarY(o.ang, o.r)
            val col = o.col

            // motion trail pointing back out along the radius
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = orbR * 0.9f
            p.color = withAlpha(col, 40)
            c.drawLine(x, y, polarX(o.ang, o.r + orbR * 3.2f), polarY(o.ang, o.r + orbR * 3.2f), p)

            p.style = Paint.Style.FILL
            p.color = withAlpha(col, 45)
            c.drawCircle(x, y, orbR * 1.9f, p)
            p.color = withAlpha(col, 110)
            c.drawCircle(x, y, orbR * 1.3f, p)
            p.color = col
            c.drawCircle(x, y, orbR, p)

            // inner glyph so the special orbs read instantly
            when (o.kind) {
                GOLD -> {
                    p.color = Color.WHITE
                    p.strokeWidth = orbR * 0.18f
                    val s = orbR * 0.5f
                    val a = o.spin
                    for (k in 0 until 4) {
                        val ang = a + k * 0.7854f * 2f
                        c.drawLine(
                            x + cos(ang) * s, y + sin(ang) * s,
                            x - cos(ang) * s, y - sin(ang) * s, p
                        )
                    }
                }
                WILD -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = orbR * 0.22f
                    p.color = colA
                    c.drawArc(rectAt(x, y, orbR * 0.55f), 90f, 180f, false, p)
                    p.color = colB
                    c.drawArc(rectAt(x, y, orbR * 0.55f), 270f, 180f, false, p)
                    p.style = Paint.Style.FILL
                }
                SPLITTER -> {
                    p.color = withAlpha(Color.BLACK, 150)
                    c.drawCircle(x, y, orbR * 0.42f, p)
                }
                else -> {
                    p.color = withAlpha(Color.WHITE, 190)
                    c.drawCircle(x - orbR * 0.25f, y - orbR * 0.28f, orbR * 0.22f, p)
                }
            }
        }
    }

    private fun drawShield(c: Canvas) {
        val half = shieldHalfSpan()
        val halfDeg = (half * 180f / PI).toFloat()
        val startDeg = (shieldAng * 180f / PI).toFloat()
        val pulse = 1f + shieldPulse * 0.35f
        arcRect.set(cx - shieldR, cy - shieldR, cx + shieldR, cy + shieldR)

        // faint full ring so you always know where the shield plane is
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = shieldW * 0.28f
        p.color = Color.argb(30, 140, 170, 230)
        c.drawCircle(cx, cy, shieldR, p)

        p.strokeCap = Paint.Cap.ROUND
        drawGlowArc(c, arcRect, startDeg - halfDeg, halfDeg, sideColor(false), pulse)
        drawGlowArc(c, arcRect, startDeg, halfDeg, sideColor(true), pulse)

        if (overdrive > 0f) {
            val t = 0.5f + 0.5f * sin(clock * 16f)
            p.style = Paint.Style.STROKE
            p.strokeWidth = shieldW * (0.3f + t * 0.3f)
            p.color = withAlpha(colGold, (90 + t * 120).toInt())
            c.drawArc(arcRect, startDeg - halfDeg, halfDeg * 2f, false, p)
        }

        // the seam between the two halves — your aiming reference
        p.strokeWidth = minDim * 0.0035f
        p.color = Color.argb(150, 255, 255, 255)
        c.drawLine(
            polarX(shieldAng, shieldR - shieldW), polarY(shieldAng, shieldR - shieldW),
            polarX(shieldAng, shieldR + shieldW), polarY(shieldAng, shieldR + shieldW), p
        )
    }

    private fun drawGlowArc(c: Canvas, r: RectF, start: Float, sweep: Float, col: Int, pulse: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = shieldW * 3.0f * pulse
        p.color = withAlpha(col, 30)
        c.drawArc(r, start, sweep, false, p)
        p.strokeWidth = shieldW * 1.8f * pulse
        p.color = withAlpha(col, 70)
        c.drawArc(r, start, sweep, false, p)
        p.strokeWidth = shieldW * pulse
        p.color = col
        c.drawArc(r, start, sweep, false, p)
    }

    private fun drawCore(c: Canvas) {
        val breathe = 1f + 0.04f * sin(clock * 2.4f) + corePulse * 0.30f
        val r = coreR * breathe
        p.style = Paint.Style.FILL

        val danger = if (phase == Phase.PLAY) {
            var closest = 1f
            for (o in orbs) closest = min(closest, ((o.r - coreR) / (shieldR * 1.6f)).coerceIn(0f, 1f))
            1f - closest
        } else 0f

        val glowCol = when {
            hp <= 1 -> 0xFFFF3355.toInt()
            overdrive > 0f -> colGold
            else -> 0xFF7FA8FF.toInt()
        }
        p.color = withAlpha(glowCol, (26 + danger * 60).toInt())
        c.drawCircle(cx, cy, r * (2.4f + danger * 0.7f), p)
        p.color = withAlpha(glowCol, 60)
        c.drawCircle(cx, cy, r * 1.5f, p)
        p.color = 0xFFF2F6FF.toInt()
        c.drawCircle(cx, cy, r, p)
        p.color = withAlpha(glowCol, 200)
        c.drawCircle(cx, cy, r * 0.55f, p)

        // core integrity pips
        p.style = Paint.Style.FILL
        val pipR = minDim * 0.011f
        for (i in 0 until maxHp) {
            val a = -1.5708f + (i - (maxHp - 1) / 2f) * 0.42f
            val x = polarX(a, coreR * 2.15f)
            val y = polarY(a, coreR * 2.15f)
            if (i < hp) {
                p.color = withAlpha(0xFFFF5577.toInt(), 60)
                c.drawCircle(x, y, pipR * 2.2f, p)
                p.color = 0xFFFF5577.toInt()
                c.drawCircle(x, y, pipR, p)
            } else {
                p.color = withAlpha(0xFFFF5577.toInt(), 45)
                c.drawCircle(x, y, pipR * 0.7f, p)
            }
        }
    }

    private fun drawParticles(c: Canvas) {
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.ROUND
        for (q in parts) {
            val t = (q.life / q.maxLife).coerceIn(0f, 1f)
            val a = (255 * t * t).toInt()
            p.color = withAlpha(q.col, a)
            if (q.streak) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = q.size * 1.4f
                c.drawLine(q.x, q.y, q.x - q.vx * 0.035f, q.y - q.vy * 0.035f, p)
                p.style = Paint.Style.FILL
            } else {
                c.drawCircle(q.x, q.y, q.size * (0.4f + t), p)
            }
        }
    }

    private fun drawTexts(c: Canvas) {
        p.style = Paint.Style.FILL
        p.typeface = fontCond
        p.textAlign = Paint.Align.CENTER
        for (t in texts) {
            val k = (t.life / t.maxLife).coerceIn(0f, 1f)
            p.textSize = t.size * (1f + (1f - k) * 0.18f)
            p.color = withAlpha(t.col, (255 * min(1f, k * 2.2f)).toInt())
            c.drawText(t.text, t.x.coerceIn(w * 0.16f, w * 0.84f), t.y, p)
        }
    }

    // ---------------------------------------------------------------- HUD

    private fun drawHud(c: Canvas) {
        if (phase == Phase.TITLE) return
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.LEFT
        p.typeface = fontBold

        val top = h * 0.055f
        p.textSize = minDim * 0.11f
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        c.drawText(score.toString(), cx, top + minDim * 0.09f, p)

        p.typeface = fontCond
        p.textSize = minDim * 0.032f
        p.color = withAlpha(Color.WHITE, 120)
        p.textAlign = Paint.Align.LEFT
        c.drawText("BEST $best", w * 0.06f, top + minDim * 0.035f, p)

        if (combo > 1) {
            val mult = Rules.multiplier(combo)
            val hot = combo >= comboForOverdrive
            p.textAlign = Paint.Align.CENTER
            p.typeface = fontBold
            p.textSize = minDim * 0.045f * (1f + shieldPulse * 0.25f)
            p.color = if (overdrive > 0f) colGold else if (hot) colWild else withAlpha(Color.WHITE, 210)
            c.drawText("x$mult   COMBO $combo", cx, top + minDim * 0.135f, p)
        }

        if (overdrive > 0f) {
            val frac = (overdrive / overdriveTime).coerceIn(0f, 1f)
            val barW = w * 0.5f
            val y = top + minDim * 0.165f
            p.style = Paint.Style.FILL
            p.color = withAlpha(colGold, 50)
            c.drawRect(cx - barW / 2, y, cx + barW / 2, y + minDim * 0.008f, p)
            p.color = colGold
            c.drawRect(cx - barW / 2, y, cx - barW / 2 + barW * frac, y + minDim * 0.008f, p)
        }
    }

    private fun drawMuteButton(c: Canvas) {
        val r = muteRect.height() * 0.5f
        val mx = muteRect.centerX()
        val my = muteRect.centerY()
        p.style = Paint.Style.STROKE
        p.strokeWidth = minDim * 0.004f
        p.color = withAlpha(Color.WHITE, if (sfx.muted) 55 else 110)
        c.drawCircle(mx, my, r, p)
        p.style = Paint.Style.FILL
        p.typeface = fontCond
        p.textAlign = Paint.Align.CENTER
        p.textSize = r * 1.25f
        p.color = withAlpha(Color.WHITE, if (sfx.muted) 70 else 190)
        c.drawText("♪", mx, my + r * 0.45f, p)
        if (sfx.muted) {
            slashPath.reset()
            slashPath.moveTo(mx - r * 0.7f, my + r * 0.7f)
            slashPath.lineTo(mx + r * 0.7f, my - r * 0.7f)
            p.style = Paint.Style.STROKE
            p.strokeWidth = minDim * 0.006f
            p.color = withAlpha(0xFFFF5577.toInt(), 210)
            c.drawPath(slashPath, p)
        }
    }

    // ------------------------------------------------------------ overlays

    private fun drawTitle(c: Canvas) {
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = fontBold

        val ty = h * 0.17f
        p.textSize = minDim * 0.135f
        p.color = colA
        c.drawText("CHROMA", cx, ty, p)
        p.color = colB
        c.drawText("CORE", cx, ty + minDim * 0.135f, p)

        p.typeface = fontCond
        p.textSize = minDim * 0.038f
        p.color = withAlpha(Color.WHITE, 150)
        val by = h * 0.79f
        c.drawText("DRAG  —  SPIN THE SHIELD", cx, by, p)
        c.drawText("TAP  —  SWAP THE COLOURS", cx, by + minDim * 0.055f, p)
        c.drawText("MATCH THE COLOUR OR THE CORE TAKES IT", cx, by + minDim * 0.11f, p)

        val pulse = 0.55f + 0.45f * sin(clock * 3.2f)
        p.typeface = fontBold
        p.textSize = minDim * 0.062f
        p.color = withAlpha(Color.WHITE, (255 * pulse).toInt())
        c.drawText("TAP TO PLAY", cx, h * 0.92f, p)

        if (best > 0) {
            p.typeface = fontCond
            p.textSize = minDim * 0.042f
            p.color = withAlpha(colGold, 220)
            c.drawText("BEST  $best", cx, ty + minDim * 0.215f, p)
            p.textSize = minDim * 0.032f
            p.color = withAlpha(Color.WHITE, 110)
            c.drawText("TOP COMBO $bestCombo   ·   RUNS $runs", cx, ty + minDim * 0.265f, p)
        }
    }

    private fun drawGameOver(c: Canvas) {
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Color.argb((min(1f, overTimer * 2.2f) * 180).toInt(), 3, 4, 10)
        c.drawRect(0f, 0f, w, h, p)

        p.textAlign = Paint.Align.CENTER
        p.typeface = fontBold
        p.textSize = minDim * 0.095f
        p.color = 0xFFFF3355.toInt()
        c.drawText("CORE BREACH", cx, h * 0.30f, p)

        p.textSize = minDim * 0.19f
        p.color = Color.WHITE
        c.drawText(score.toString(), cx, h * 0.46f, p)

        p.typeface = fontCond
        p.textSize = minDim * 0.042f
        if (newBest) {
            val pulse = 0.5f + 0.5f * sin(clock * 6f)
            p.color = withAlpha(colGold, (255 * (0.6f + 0.4f * pulse)).toInt())
            c.drawText("NEW BEST!", cx, h * 0.52f, p)
        } else {
            p.color = withAlpha(Color.WHITE, 130)
            c.drawText("BEST  $best", cx, h * 0.52f, p)
        }

        p.textSize = minDim * 0.036f
        p.color = withAlpha(Color.WHITE, 120)
        c.drawText("TOP COMBO THIS RUN  $bestComboRun", cx, h * 0.575f, p)
        c.drawText("SURVIVED  ${time.toInt()}s", cx, h * 0.62f, p)

        if (overTimer > 0.7f) {
            val pulse = 0.55f + 0.45f * sin(clock * 3.4f)
            p.typeface = fontBold
            p.textSize = minDim * 0.062f
            p.color = withAlpha(Color.WHITE, (255 * pulse).toInt())
            c.drawText("TAP TO RETRY", cx, h * 0.80f, p)
        }
    }

    // ------------------------------------------------------------ misc

    private val tmpRect = RectF()
    private fun rectAt(x: Float, y: Float, r: Float): RectF {
        tmpRect.set(x - r, y - r, x + r, y + r)
        return tmpRect
    }

    private fun withAlpha(col: Int, a: Int): Int =
        (col and 0x00FFFFFF) or ((a.coerceIn(0, 255)) shl 24)
}
