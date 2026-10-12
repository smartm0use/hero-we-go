# Build

## Prerequisites

- **JDK 17**
- Android SDK **platform 35** and its build tools
- Internet access for the initial Gradle/dependency downloads

Open this repository in Android Studio, or set `JAVA_HOME` and `ANDROID_HOME`
for your machine. The Gradle wrapper is included and verifies the downloaded
Gradle 8.9 distribution checksum.

## Lite ARM64 — current build

```bash
./gradlew :app:assembleLite :app:testLiteUnitTest :app:lintLite
```

Windows:

```powershell
.\gradlew.bat :app:assembleLite :app:testLiteUnitTest :app:lintLite
```

Output: **`app/build/outputs/apk/lite/app-lite.apk`**.

Current source version: **1.0-lite-arm64** (`versionCode 1`,
`versionName 1.0` + `-lite-arm64` suffix in `app/build.gradle`).
Lite keeps the same app features and VLC player but packages only ARM64
native libraries. Its APK is roughly **54 MB**, compared with roughly
**200 MB** for a universal build; exact sizes vary with the build.
Installed storage usage is different.

## Universal — optional

```bash
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. It includes ARM64,
ARM32, x86 and x86_64 libraries. Both variants share the same Java sources;
the ABI filter is configured in `app/build.gradle`.

## Signing and IDs

Both builds use debug signing. The key is generated locally by Android
tooling and is **not included in this repository**. To update an existing
installation, use the same signing key and an appropriate `versionCode`;
a build signed on another machine generally cannot replace an existing
installation directly. These instructions do not constitute a Play Store
release configuration.

The Android application ID remains **`it.hero3.remote`** for compatibility
with earlier installations under the name HERO3 Remote.

## Tests

Builds, unit tests and Android Lint have been run during development
(see the `assembleLite` / `testLiteUnitTest` / `lintLite` command above).
Hardware behavior still requires testing on a real camera/phone; simulated
HTTP tests do not certify compatibility with every firmware.
