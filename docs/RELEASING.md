# Release signing and publishing

The public package keeps application ID `au.wazza.cuecam`. A version 1.0.0 release uses a stable private signing key; debug APKs use Android's development key and are not suitable for publication.

## Keep signing material outside Git

Use a dedicated folder under the current user's local application-data directory, for example `%LOCALAPPDATA%\WazzasQCam\`. Keep the release keystore and its passwords there or in a password manager. Restrict the folder to the account that owns the key. Never put the keystore, passwords, or signed APK in the Git working tree.

Create a new key only once and keep a protected backup in a separate secure location:

```powershell
keytool -genkeypair -v `
  -keystore "$env:LOCALAPPDATA\WazzasQCam\release-key.jks" `
  -alias wazzas-qcam -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` prompts for passwords; do not put them on the command line. Keep them in a password manager. To build a signed release, create a private properties file outside the repository containing only:

```properties
storeFile=release-key.jks
keyAlias=wazzas-qcam
```

Set `QCAM_RELEASE_STORE_PASSWORD` and `QCAM_RELEASE_KEY_PASSWORD` in the current build process from the password manager, then run:

```powershell
.\gradlew.bat assembleRelease `
  -PqcamReleaseSigningProperties="$env:LOCALAPPDATA\WazzasQCam\release-signing.properties"
```

On macOS or Linux, use the same Gradle property and set the two environment variables from the platform's password manager. The build signs `app/build/outputs/apk/release/app-release.apk` only when the external properties file and both environment values are present. Otherwise, `assembleRelease` produces an unsigned APK; do not publish it.

Verify the signed APK before publishing:

```sh
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
```

Windows PowerShell can calculate the digest with `Get-FileHash -Algorithm SHA256`. Record the exact version, commit, signing-certificate SHA-256 and APK SHA-256 in the release provenance. Never publish the signing key or password values.

## Existing debug installs

An Android install signed with the debug key cannot be updated by an APK signed with this stable release key. Do not remove an existing debug install until its app-private scripts have been migrated or the owner explicitly accepts losing them. Videos already saved in `Movies/WazzaCueCam` are outside app-private storage.

## Public release checklist

1. Run `clean testDebugUnitTest assembleDebug` and build the signed release APK.
2. Verify the release APK on a clean test emulator and complete the physical-device checks without wiping existing app data.
3. Inspect the public source tree and its full public history for credentials and local-only files. Keep the local `upstream` remote and development checkpoint tags out of the new repository.
4. Create the GitHub release from the reviewed public snapshot, attach `Wazza-QCam-1.0.0.apk`, and publish notes and checksums.
5. Open the public README and release from a signed-out browser session to verify a new visitor can find and install the app.
