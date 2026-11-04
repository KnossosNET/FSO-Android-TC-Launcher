# FSO Android TC Launcher

Native Android Studio project for independently installed FreeSpace Open total conversions. Each game is a separate application with its own application ID, name, icon, packaged engine, content installation and pilot storage.

## Included applications

| Module | Application ID | Nebula ID | Content baseline |
| --- | --- | --- | --- |
| Fotg | com.fsolauncher.fotg | FotG_R | 1.1.1 or newer |
| fs2demo | com.fsolauncher.fs2demo | fs2_org_demo | 1.10.2 or newer |

FotG's identity, artwork and default FSO arguments come from the supplied Knossos.NET standalone release. No game packages are embedded in an APK. FotG's initial complete download is approximately 2.57 GB at version 1.1.1.

## Layout

- shared/src/main: shared native Android launcher, SDL3 host, touch overlay, JNI input bridge and resources.
- shared/core: plain Java Nebula client, dependency resolver, downloader and transactional content installer.
- shared/version.gradle: common Android build configuration.
- versions/Fotg and versions/fs2demo: independent Android application modules.
- template: instructions and starter module for another TC.
- scripts: engine import, new-version creation, development signing and native-page validation.
- fixtures: public repository/API samples used by integration tests.
- licenses and THIRD_PARTY_NOTICES.md: copied source attribution and distribution obligations.
- upstream: local reference checkouts, excluded from source control and compilation.

## Build in Android Studio

Use JDK 17, Android SDK Platform 36, NDK 29.0.14033849 and CMake 3.22.1. Open this root folder, let Gradle sync, and select Fotg or fs2demo as the run configuration. Minimum Android version is 9 / API 28; the builds target API 36. Each version's abis list determines the architectures packaged in its APK.

The included engine was imported separately into each version from the local FSO Android prebuilts. engine-provenance.json records source filenames and SHA-256 values. SDL3 Java/header sources and native SDL must match; this snapshot uses SDL 3.4.13. Engines export android_main and are loaded only from the installed APK's native library directory.

For a fresh checkout, point local.properties at your SDK, then import your own engine:
    python scripts/import-engine.py Fotg D:/Fso-Android-Prebuilts/fso_android/jniLibs
    python scripts/import-engine.py fs2demo D:/Fso-Android-Prebuilts/fso_android/jniLibs

The importer automatically detects every supported ABI subfolder in the source (arm64-v8a, armeabi-v7a, x86_64 and x86), imports one release engine and its dependencies for each, and updates both version.json's abis list and engine-provenance.json. The previous abis list does not restrict discovery. DEBUG libraries are excluded unless explicitly selected as the engine. The source directory must be separate from the destination version's jniLibs.

To intentionally package fewer architectures, use an explicit filter:
    python scripts/import-engine.py Fotg D:/jniLibs --abis arm64-v8a x86_64

All selected inputs are validated and copied to staging before replacing existing binaries. If replacement fails, the previous native folders and metadata are restored. Unsupported source subfolders are ignored; a supported ABI folder with missing SDL3 or an ambiguous engine causes the import to fail without modifying the previous installation.

Build debug APKs:
    .\gradlew.bat :Fotg:assembleDebug :fs2demo:assembleDebug

Build signed release APKs:
    .\gradlew.bat releaseApks

Signed outputs are collected in dist/com.fsolauncher.fotg-signed.apk and dist/com.fsolauncher.fs2demo-signed.apk. Each module also contains its APK under build/outputs/apk/release.

A local DEVELOPMENT key is supplied only in this working copy under signing/ and is excluded from source control. These binaries are suitable for testing. Configure a private production key in signing.properties before store distribution. A new working copy can generate a development key with:
    powershell -ExecutionPolicy Bypass -File scripts/create-development-key.ps1

Production signing.properties has the keys storeFile, storePassword, keyAlias and keyPassword. Keep it and all keystores private. Retain the signing key used for distributed applications so future APKs can update them. Do not publish development keys as production identities.

Google Play bundles can be built with:
    .\gradlew.bat :Fotg:bundleRelease :fs2demo:bundleRelease

## Content installation and updates

The launcher presents only its own TC. Install / Update starts a user-requested foreground transfer with notification progress and a Pause action. Check for updates reads repo_minimal.json and fetches complete metadata through GET /api/1/mod/json/{id}/{version}, using the mirrors from Knossos.NET. Playing an already installed game does not require a network request.

During installation, the launcher and expanded download notification show the number of installed packages, the total and how many remain. Counts include content dependencies and reused installed packages; engine dependencies remain excluded. A package advances the counter only after all its archives and files have passed verification. Byte progress continues to describe the current download.

Required packages and, by default, recommended packages are installed. Explicitly requested dependency packages are included even when optional. Dependencies are recursively resolved with compatible semantic versions, including backtracking when different mods constrain the same dependency. Engine dependencies are excluded by repository type; the canonical FSO ID is also excluded before an API lookup. All content dependencies are resolved again during updates, even if the root TC version is unchanged.

Public Nebula repositories are supported; private/authenticated releases are not configured. Supported version constraints include exact versions, comparison sets, caret, tilde, wildcards and alternatives. Package environment expressions are evaluated for Android and the device ABI. Unknown expressions fail with a message rather than selecting another platform's package.

Archives use HTTPS, mirror fallback, SHA-256 verification and validated byte-range resumption. ZIP and 7z archives are supported. Every extracted file must appear in the package manifest and pass its SHA-256 checksum. Paths cannot escape the installation, archive links are refused, and downloadable native/JVM executable files are refused. Game scripts are treated as game data, never loaded as Android classes or native libraries.

A new immutable content graph is prepared next to the active installation. Only after all packages and dependencies succeed does installed.json atomically activate the complete graph. A failed update preserves the prior installation. Unchanged packages can reuse the installed mod folder; completed verified archives are retained during an interrupted transaction so resuming does not repeat completed downloads. Downloads and obsolete content are removed after a successful activation. Allow space for old and new content plus download archives during installation.

The runtime working directory is Android/data/{applicationId}/files/game. -mod search paths follow Nebula mod_flag order, selected development package folders when applicable and resolved dependencies. Pilot/config files use FSO's per-application SDL preference storage; content cleanup only removes immutable content folders. Game and installer processes hold the same filesystem lock so content is never replaced while FSO is running.

Update an engine by replacing that version's jniLibs through the import script, incrementing versionCode/versionName and distributing a new signed APK or Play update. The application never downloads or self-updates executable engine blobs.

## Touch controls

The shared overlay is copied from FSO Android Wrapper, also used by Knossos.NET. It includes the radial weapon, target and communications controls, mouse flight pad, afterburner ring, top shortcuts, keyboard toggle and HUD visibility modes.

Standard Android gamepads continue through SDL's input system. The optional direct BLE Steam Controller HID path is disabled; no Bluetooth runtime permissions are requested. USB permission receivers are restricted to the application, and Android back gestures generate the same Escape input as the physical Back button.

Each version packages src/main/assets/touch.json. Developers can remap bridge codes, replace individual radial actions or button bindings, and choose the initial HUD mode. There is no user remapping screen and the profile is never fetched from Nebula. See template/readme.md. Keep the stable Java namespace/JNI symbols unless rebuilding the bridge as well; applicationId remains independently configurable.

The launcher offers an ES / VK graphics selector, with Vulkan selected by default, followed by a Show FPS checkbox (off by default). Preferences are saved separately for each installed TC. The selected graphics backend adds exactly one of -opengl or -vulkan, and Show FPS adds -fps only when checked. These controls override conflicting copies of those switches in game.json. Vulkan is an optional device capability in the manifest so ES-only devices can install the application.

The footer displays Launcher Version: {shared version} / {TC version}. Edit shared/launcher-version.properties to change the common launcher version. Edit versionName in a TC's versions/{TC}/version.json for its manually maintained launcher variant version. versionCode remains the Android update counter and must increase for distributed APK updates.

Engine Version is queried from the bundled native engine on application startup using -get_flags json_v2. A separate :flags SDL activity receives the engine's setFlagsJson JNI callback and returns version.full. The displayed value is never inferred from filenames or engine-provenance.json. A failed query displays Unavailable; a stalled request ends after 30 seconds. This mechanism is adapted from Knossos.NET's FlagsActivity and AndroidHelper.

The activity result is stored synchronously inside the JNI callback, before SDL closes the flags activity when its native entry point returns. Diagnostic messages use the FSOFlags logcat tag; package progress uses FSOInstall.

Validate both graphics backends, performance, controls and any TC-specific graphics flags on the intended physical devices before distribution.

## Validation

    .\gradlew.bat :core:test
    .\gradlew.bat :core:probeNebula
    .\gradlew.bat :core:probeDemo
    .\gradlew.bat :Fotg:lintDebug :fs2demo:lintDebug
    python scripts/check-native-pages.py dist/com.fsolauncher.fotg-signed.apk

The live probe downloads only FotG's small FG_Core package, verifies the real API graph, extracts 7z data and checks every file checksum. It does not download the full game. See VALIDATION.md for the checks performed on this working copy and the remaining device checks.

probeDemo separately downloads the complete FS2 Demo content (approximately 50 MB), verifies/extracts all its packages, checks the resulting installation and deletes the test content.

## Google Play preparation

The project uses API 36, 64-bit native code, app-specific storage, HTTPS, a non-exported game activity/service and a declared dataSync foreground service. Native ELF load segments are checked for 16 KB page support. Engines are installed with the application; Nebula distributes only game data.

These choices support store submission; approval is not guaranteed. Before publishing, use production signing and an AAB, complete Play Console declarations for the foreground service, content rating, privacy/data safety, and verify branding/content rights and third-party source/license obligations. Test Android 9 through current releases, 16 KB devices, large downloads, background interruptions, real gameplay and upgrade compatibility. The foreground transfer stops when Android imposes its dataSync timeout; users can resume from the launcher.

Official references reviewed on 2026-11-04:
- https://developer.android.com/google/play/requirements/target-sdk
- https://developer.android.com/guide/practices/page-sizes
- https://support.google.com/googleplay/android-developer/answer/16559646
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/timeout

See THIRD_PARTY_NOTICES.md for exact upstream snapshots and copied files.

## GitHub draft releases

The workflow in .github/workflows/tag-release.yml builds every configured game when any tag is pushed, verifies all APKs and attaches them to a new draft release. Re-runs can update draft assets but never modify an already published release. No release is automatically published.

Configure ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD as repository Actions secrets once. Use the same signing key as previously distributed APKs. The workflow deliberately fails instead of generating an incompatible random key when signing secrets are missing.

See .github/RELEASES.md for complete setup, required repository files, tagging commands and local checks.
