# Version 1.0.0 build record

Prepared on 2026-10-27 from the Wazza's QCam working tree for the `v1.0.0` source snapshot.

## Build inputs

- Application ID: `au.wazza.cuecam`
- Version name / code: `1.0.0` / `2`
- Compile and target SDK: 35
- Local build: JDK 17, Android SDK Platform 35, Gradle wrapper
- Build command: `testDebugUnitTest assembleDebug assembleRelease`
- Tests: 6 passed, 0 failed, 0 skipped
- Release lint vital task: passed

## Local artifact checksums

These hashes are for local build artifacts only. The release variant is unsigned and must not be distributed as an installable release.

| Artifact | SHA-256 | Status |
| --- | --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | `EDA9C0DC8F24BFA7C3C688D3DBDF7B026C61BA1C515518F3830EDA8ECFD272D6` | Debug-signed; development only |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `79AA48DF4EAE460C892B6822986F0D5D98141C432912B03DFC3F3C530C3ECE43` | Unsigned; not for distribution |

No permanent release keystore was configured for this build. The signed release checksum and certificate fingerprint must be added after a project-owner-controlled key is available.

## Device verification

- OPPO Reno15 Pro 5G, Android 16: updated in place with the matching debug certificate; existing app data was not cleared. The script and settings preference files remained present.
- The CameraX microphone graph showed live green readings during on-device recording.
- Open Video listed the newest `Movies/WazzaCueCam` items first with filename, duration, and resolution. The system player then requested broader media access; playback was not confirmed during this pass.
- Android 15 Pixel 8 emulator: portrait and landscape splash screens and app layouts were captured. The startup asset pool matched the launch orientation.

The live microphone test ran longer than intended. The resulting test clips remain in `Movies/WazzaCueCam`; no existing recordings were deleted.

## Publication status

The source snapshot and local tag are prepared for review. No public repository, GitHub release, or signed public APK has been published. GitHub CLI authentication must be renewed and stable release-signing credentials supplied before publication.
