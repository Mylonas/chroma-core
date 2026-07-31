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

- minSdk 26, targetSdk 34, portrait only.
