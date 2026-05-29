# hands-free-incident-report-mobile

This repository contains an Android application (Kotlin) for capturing, encrypting, and uploading incident videos to a remote API.

Summary
- Android application using Kotlin and Gradle (Kotlin DSL).
- Application namespace / package: `com.handsfree_incident_report_mobile`.
- Min SDK: 35, Target SDK / Compile SDK: 36 (see `app/build.gradle.kts`).

Key features and behavior (verifiable in source)
- Captures video using AndroidX Camera libraries (see `app/build.gradle.kts` dependencies).
- Encrypts recorded videos locally before upload. Implementation details visible in `app/src/main/java/com/handsfree_incident_report_mobile/data/VideoRepository.kt`:
	- Generates a 32-byte AES session key and a 16-byte IV and encrypts video data with AES/CBC/PKCS5Padding.
	- Encrypts the AES session key using RSA OAEP (SHA-256, MGF1) with a server-provided public key.
	- Uploads encrypted video and metadata (latitude, longitude, orientation, created_at, encrypted session key) to the server.
- Obtains location and device orientation using Google Play Services location/orientation APIs.
- Uses Auth0 `CredentialsManager` to obtain a bearer token for authenticated API requests (manifest placeholders include an Auth0 domain and scheme).

Network / API
- The code calls the base URL `https://api.hands-free-incident-report.ch` (see `VideoRepository`), and interacts with endpoints such as `/public_key`, `/new_report/` and `/upload_video/`.
- HTTP client usage is implemented with OkHttp (see `VideoRepository`).

Permissions and manifest
- The app requests common runtime permissions for camera, audio recording, and location in `app/src/main/AndroidManifest.xml`:
	- `INTERNET`, `CAMERA`, `RECORD_AUDIO`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, plus read/write external storage entries.
- Main launcher activity: `.ui.MainActivity`.

External dependencies
- Build tooling: `com.android.application` Gradle plugin `9.1.0`.
- Runtime: `androidx.core:core-ktx` `1.10.1`.
- Runtime: `androidx.appcompat:appcompat` `1.6.1`.
- Runtime: `com.google.android.material:material` `1.10.0`.
- Runtime: `androidx.constraintlayout:constraintlayout` `2.1.4`.
- Runtime: `androidx.navigation:navigation-fragment-ktx` `2.6.0`.
- Runtime: `androidx.navigation:navigation-ui-ktx` `2.6.0`.
- Runtime: `com.google.android.gms:play-services-location` `21.3.0`.
- Runtime: `androidx.navigation:navigation-fragment-ktx` `2.9.8`.
- Runtime: `androidx.navigation:navigation-ui-ktx` `2.9.8`.
- Runtime: `com.auth0.android:auth0` `3.14.0`.
- Runtime: `org.jetbrains.kotlinx:kotlinx-coroutines-play-services` `1.7.3`.
- Runtime: `androidx.camera:camera-core` `1.5.3`.
- Runtime: `androidx.camera:camera-camera2` `1.5.3`.
- Runtime: `androidx.camera:camera-lifecycle` `1.5.3`.
- Runtime: `androidx.camera:camera-video` `1.5.3`.
- Runtime: `androidx.camera:camera-view` `1.5.3`.
- Runtime: `androidx.camera:camera-extensions` `1.5.3`.
- Test: `junit:junit` `4.13.2`.
- Android test: `androidx.test.ext:junit` `1.1.5`.
- Android test: `androidx.test.espresso:espresso-core` `3.5.1`.

Build and run (local)
Prerequisites: Android SDK (matching compile/target), JDK 8 compatibility as configured in the Gradle file.

Standard Gradle wrapper commands from the repository root:

```bash
./gradlew assembleDebug      # build debug APK (on Windows use gradlew.bat)
./gradlew installDebug       # build and install to a connected device/emulator
```

Notes and references
- See `app/build.gradle.kts` for declared dependencies (Camera libraries, Play Services Location, Auth0, coroutines, AndroidX Navigation, Material).
- Sensitive behavior: the app performs cryptographic operations and transmits encrypted blobs to a server — review `VideoRepository.kt` for the exact flow and error handling.
- Do not run on devices without verifying permissions and Play Services availability.

If you want, I can also:
- Add a short Development section with how to configure the Auth0 placeholders and emulator/device setup.
- Add usage notes or a developer-oriented checklist for testing uploads.
