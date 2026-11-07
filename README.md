# NOOP

NOOP is an on-device wearable companion for WHOOP straps, with experimental support for other devices. It stores wearable data locally and computes sleep, recovery, and strain on the device.

This is the working repository for NOOP. It includes the Android, macOS, iOS, and watchOS app source. The private test corpus, device captures, research artifacts, and signed distributions are excluded.

Signed Android updates are available from [GitHub Releases](https://github.com/ronboger/noop-public/releases/latest). Starting with 11.10.3, NOOP checks these releases without a GitHub token.

## Build from source

- **Android:** Install Android Studio or the Android SDK with platform 35 and use JDK 17 or 21. Run `./gradlew :app:assembleFullDebug` from the `android` directory. The debug build uses the local Android debug key.
- **Apple platforms:** Install Xcode and XcodeGen, run `xcodegen generate` from the repository root, and open `Strand.xcodeproj`. Select the Strand, NOOPiOS, or NOOPWatch scheme for the platform you want to build.

NOOP is source-available under the [PolyForm Noncommercial License](LICENSE). See [NOTICE](NOTICE) and [ATTRIBUTION](ATTRIBUTION.md) for third-party credits and license information.
