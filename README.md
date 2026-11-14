# Plonker

A YouTube Music client for Android, with Android TV and Wear OS companions.

Plonker is a fork of [BitChord](https://github.com/kushagrasinghx/BitChord) by Kushagra Singh and its contributors, and stays under the same licence.

> Plonker is not affiliated with, endorsed by, or connected to YouTube or Google in any way. Use it at your own discretion.

## Layout

| Folder | What it is |
|---|---|
| `app/` | The phone app |
| `shared/`, `sharedUi/` | Code and Compose UI shared between the apps |
| `tv/` | Android TV app ([notes](tv/README.md)) |
| `wearos/` | Wear OS app ([notes](wearos/README.md)) |
| `native/` | C++ audio analysis, mixing and FFmpeg glue |
| `backend/` | Listen Together party server ([notes](backend/README.md)) |
| `web/` | Web player and its browser extension ([notes](web/README.md)) |
| `docs/` | Technical notes |

## Building

Requires JDK 17 and the Android SDK.

```bash
./gradlew :app:assembleDevDebug
```

For a signed release build, copy `keystore.properties.example` to `keystore.properties`, fill it in, then:

```bash
./gradlew :app:assembleProdRelease
```

Tests:

```bash
./gradlew :sharedUi:jvmTest :app:testDevDebugUnitTest
```

## Licence

GPL-3.0. See [LICENSE](LICENSE). Third-party code under `native/third_party/` and `web/app/src/playback/automix/` carries its own licence files.
