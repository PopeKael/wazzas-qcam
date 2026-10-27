# Third-party notices

## CueCam

Wazza's QCam is a modified derivative of [CueCam](https://github.com/lustsazeus-lab/CueCam), Copyright 2026 lustsazeus-lab. CueCam is licensed under Apache License 2.0. The original `LICENSE` is included at the repository root. The upstream tree at the source revision used for this project did not contain a `NOTICE` file; this project's `NOTICE` records the upstream attribution and the changes made here.

## Android and Kotlin dependencies

The Android application uses AndroidX Activity, AndroidX Core, and AndroidX CameraX. These libraries and Kotlin's standard library are published under Apache License 2.0. The exact versions are declared in `app/build.gradle.kts`; the APK's resolved runtime dependency graph can be listed with:

```sh
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

The unit-test configuration also references `org.json:json:20240303`. It is a test-only dependency and is not packaged in the application APK. The library's own license terms apply to that dependency; see its upstream project at [JSON-java](https://github.com/stleary/JSON-java).

## Branded artwork

The seven Wazza's QCam splash images were supplied and approved by project owner Warren Gerdes for this application's distribution. Selected images are reused for the project page. They are not CueCam assets and are not relicensed under Apache-2.0 by the source-code license. Redistribution of the artwork outside this application and its project page requires permission from the project owner.

No other third-party photographs, videos, fonts, or bundled media are included in the release source.
