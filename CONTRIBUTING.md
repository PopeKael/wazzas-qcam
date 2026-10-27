# Contributing

Thanks for helping improve Wazza's QCam. The project is maintained by Warren “Wazza” Gerdes, with community feedback welcome.

## Report a bug or suggest an improvement

Open a GitHub Issue and include:

- Phone model and Android version.
- Front or rear camera, selected recording quality, and portrait or landscape orientation.
- Short, numbered steps to reproduce and what you expected to happen.
- Relevant non-sensitive log output or a screenshot with private details removed.

Do not upload private scripts, personal documents, or recordings that identify someone without their permission. A support request does not require payment.

## Changes

Keep changes focused, preserve the offline/local-storage design, and avoid adding permissions, analytics, advertising, or network services without discussion. Run the existing unit tests and a debug build:

```sh
./gradlew clean testDebugUnitTest assembleDebug
```

For camera or layout changes, include emulator evidence and say which physical devices were actually tested. Do not claim a device or recording mode passed without testing it.

By submitting a contribution, you agree that your changes may be distributed under Apache License 2.0, consistent with the project license.
