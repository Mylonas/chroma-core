# Chroma Core

A one-thumb arcade survival game for Android. Neon orbs converge on your core from
every direction; you spin a two-tone shield around it and swap its colours to catch
them. Miss one — or catch it on the wrong colour — and the core takes a hit.

No game engine, no third-party dependencies: pure Kotlin on a `SurfaceView` canvas,
with procedurally synthesised sound (no audio assets at all).

## How it plays

| Input | Action |
| --- | --- |
| Drag horizontally (anywhere) | Spin the shield. A full screen sweep is a bit more than one rotation. |
| Tap | Swap which half of the shield is cyan and which is magenta. |
| Second finger tap (while dragging) | Same swap, without letting go of the spin. |
| Tap the ♪ badge (top right) | Mute / unmute. |
| Back | Bail out to the title screen. |

Block an orb with the **matching colour**. Block it dead-centre on that half for a
**PERFECT** (double points, a slice of hit-stop, a haptic tick).

### Orb types

| Orb | Behaviour |
| --- | --- |
| Cyan / magenta | Must be blocked by the matching shield half. |
| White (wild) | Either half blocks it. Appears once the run heats up. |
| Black-cored splitter | Splits into two coloured orbs just outside the shield ring. |
| Gold | Any half blocks it. Worth 100 base, and restores a lost core pip. |

### The hooks

- **Combo → multiplier.** Every clean block climbs a pentatonic ladder in the audio,
  and the multiplier rises to ×10. Any hit resets it to zero — the streak is the
  thing you're really protecting.
- **Overdrive.** Every 15 blocks in a row: six seconds of wide shield, colour-blind
  blocking and double score. It arrives exactly when you're already on a roll.
- **Instant retry.** Death → score → one tap and you're playing again in under a
  second. No menus in the loop.
- **A ramp you can feel.** Spawn rate and orb speed climb for the first ~95 seconds,
  with a "SPEED UP" beat every 25 seconds, then keep creeping.
- Persistent best score, best combo and run count on the title screen.

## The aiming flaw, and how it was found

Chroma Core shipped before Tether and Fuse, and unlike them its balance was
never measured — it was tuned by eye. Running a bot through a JavaScript port of
its rules afterwards showed the game was broken:

| | median run | score | best combo |
| --- | --- | --- | --- |
| skilled | 12.9s | 50 | 2.9 |
| average | 13.4s | 57 | 2.8 |
| careless | 12.6s | 54 | 2.3 |

Every run was about thirteen seconds and **a careless player did as well as a
careful one** — the definition of a game with no skill in it.

The cause: centring the shield on an incoming orb is the intuitive play, and it
lands the orb exactly on the seam where the two halves meet, so which colour
caught it was a coin flip. `drawShield` then painted a bright white line on that
seam, making the single worst aiming point the most salient mark on the screen.
Isolating it, a human-ish player who aims that way survives **8.8 seconds and
scores 19**.

Three changes:

- **A 10° neutral band at the seam.** Either colour blocks there, so the obvious
  action is safe. Aiming a half's midpoint still earns PERFECT (its window is
  16°–42°, clear of the band), so mastery is rewarded rather than required.
- **The shield marks the half midpoints, not the seam.** The marked spot and the
  rewarded spot are now the same spot.
- **A constant spawn radius** instead of the distance to the far corner, which
  had made your reaction time depend on which direction an orb happened to come
  from, and left the screen empty for the first seven seconds of every run — 23%
  of a run had nothing on it at all. Orbs fade in rather than popping.

After:

| | median run | score | best combo |
| --- | --- | --- | --- |
| skilled | 64.1s | 6,312 | 56.5 |
| average | 49.6s | 2,639 | 40.9 |
| careless | 25.9s | 608 | 14.2 |

Empty screen: 1.7%. Centring is now survivable but scores about half what
half-aiming does, which is the gradient the game wanted all along.

## Building

The project has no wrapper JAR checked in.

**Android Studio (easiest):** `File → Open` this folder. Studio will offer to
generate the Gradle wrapper and download the SDK bits, then hit Run.

**Command line**, with a JDK 17 and the Android SDK installed
(`ANDROID_HOME` set, or a `local.properties` with `sdk.dir=...`):

```bash
gradle wrapper && ./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

**GitHub Actions:** push to `master` or `dev` (or run the workflow manually) and
`.github/workflows/android.yml` builds the debug APK on a GitHub-hosted runner and
uploads it as the `chroma-core-debug-apk` artifact. That is the zero-install path —
download the artifact, unzip, sideload.

Install to a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Layout

```
app/src/main/java/com/mikmy/chromacore/
  MainActivity.kt   immersive fullscreen host
  GameView.kt       SurfaceView + render thread (GPU canvas), touch plumbing
  Game.kt           all game state, simulation and rendering
  Sfx.kt            procedural synth + software mixer over one AudioTrack
```

Tuning knobs live at the top of `Game.kt` (`perfectWindow`, `comboForOverdrive`,
`overdriveTime`, `maxHp`) and in `updatePlay` / `spawnOrb` for the difficulty ramp.

- minSdk 26, targetSdk 35, portrait only.
