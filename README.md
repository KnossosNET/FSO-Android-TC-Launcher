# FSO Android TC Launcher

Native Android Studio project for independently installed FreeSpace Open total conversions. Each game is a separate application with its own application ID, name, icon, packaged engine, content installation and pilot storage.

<p float="left">
  <img src="https://i.imgur.com/WyVKcgR.jpeg" width="32%" />
  <img src="https://i.imgur.com/mg8EmNt.jpeg" width="32%" />
  <img src="https://i.imgur.com/8SOWQra.jpeg" width="32%" />
</p>

## Included applications

| Module | Application ID | Nebula ID | Content baseline |
| --- | --- | --- | --- |
| Fotg | com.fsolauncher.fotg | FotG_R | 1.1.1 or newer |
| fs2demo | com.fsolauncher.fs2demo | fs2_org_demo | 1.10.2 or newer |
| wcsaga | com.fsolauncher.wcsaga | Wing_Commander_Saga | 0.9.65 or newer |

## Layout

- shared/src/main: shared native Android launcher, SDL3 host, touch overlay, JNI input bridge and resources.
- shared/core: plain Java Nebula client, dependency resolver, downloader and transactional content installer.
- shared/version.gradle: common Android build configuration.
- versions/Fotg and versions/fs2demo: independent Android application modules.
- template: instructions and starter module for another TC.
- scripts: engine import, new-version creation, development signing and native-page validation.

## Build in Android Studio

Use JDK 17, Android SDK Platform 36, NDK 29.0.14033849 and CMake 3.22.1. Open this root folder, let Gradle sync, and select Fotg or fs2demo as the run configuration. Minimum Android version is 9 / API 28; the builds target API 36. Each version's abis list determines the architectures packaged in its APK.

The included engine was imported separately into each version from the local FSO Android prebuilts. engine-provenance.json records source filenames and SHA-256 values. SDL3 Java/header sources and native SDL must match; this snapshot uses SDL 3.4.13. Engines export android_main and are loaded only from the installed APK's native library directory.

For a fresh checkout, point local.properties at your SDK, then import your own engine:<br />
    python scripts/import-engine.py Fotg D:/Fso-Android-Prebuilts/fso_android/jniLibs<br />
    python scripts/import-engine.py fs2demo D:/Fso-Android-Prebuilts/fso_android/jniLibs<br />

The importer automatically detects every supported ABI subfolder in the source (arm64-v8a, armeabi-v7a, x86_64 and x86), imports one release engine and its dependencies for each, and updates both version.json's abis list and engine-provenance.json. The previous abis list does not restrict discovery. DEBUG libraries are excluded unless explicitly selected as the engine. The source directory must be separate from the destination version's jniLibs.

To intentionally package fewer architectures, use an explicit filter:<br />
    python scripts/import-engine.py Fotg D:/jniLibs --abis arm64-v8a x86_64

All selected inputs are validated and copied to staging before replacing existing binaries. If replacement fails, the previous native folders and metadata are restored. Unsupported source subfolders are ignored; a supported ABI folder with missing SDL3 or an ambiguous engine causes the import to fail without modifying the previous installation.


<br />
Build debug APKs:<br />
    .\gradlew.bat :Fotg:assembleDebug :fs2demo:assembleDebug<br />
<br />
Build signed release APKs:<br />
    .\gradlew.bat releaseApks<br />
<br />
	
	
Signed outputs are collected in dist/com.fsolauncher.fotg-signed.apk and dist/com.fsolauncher.fs2demo-signed.apk. Each module also contains its APK under build/outputs/apk/release.

A local DEVELOPMENT key is supplied only in this working copy under signing/ and is excluded from source control. These binaries are suitable for testing. Configure a private production key in signing.properties before store distribution. A new working copy can generate a development key with:<br />
    powershell -ExecutionPolicy Bypass -File scripts/create-development-key.ps1<br />

Production signing.properties has the keys storeFile, storePassword, keyAlias and keyPassword. Keep it and all keystores private. Retain the signing key used for distributed applications so future APKs can update them. Do not publish development keys as production identities.



Google Play bundles can be built with:<br />
    .\gradlew.bat :Fotg:bundleRelease :fs2demo:bundleRelease<br />


	
## Add / Change the FSO build of a module and build APKs

Each module have their own FSO version, and the java loader looks for "libfso.so" along with all other dependencies. In order to add or update the include FSO build, run these commands.
<br />
python scripts/import-engine.py Fotg "D:\path\to\jniLibs"<br />
python scripts/import-engine.py fs2demo "D:\path\to\jniLibs"<br />
...

## Content installation and updates

The launcher presents only its own TC. Install / Update starts a user-requested foreground transfer with notification progress and a Pause action. Check for updates reads repo_minimal.json and fetches complete metadata through GET /api/1/mod/json/{id}/{version}, using the mirrors from Knossos.NET. Playing an already installed game does not require a network request.

During installation, the launcher and expanded download notification show the number of installed packages, the total and how many remain. Counts include content dependencies and reused installed packages; engine dependencies remain excluded. A package advances the counter only after all its archives have passed verification and extraction has completed successfully. Byte progress continues to describe the current download.

Required packages and, by default, recommended packages are installed. Explicitly requested dependency packages are included even when optional. Dependencies are recursively resolved with compatible semantic versions, including backtracking when different mods constrain the same dependency. Engine dependencies are excluded by repository type; the canonical FSO ID is also excluded before an API lookup. All content dependencies are resolved again during updates, even if the root TC version is unchanged.

Public Nebula repositories are supported; private/authenticated releases are not configured. Supported version constraints include exact versions, comparison sets, caret, tilde, wildcards and alternatives. Package environment expressions are evaluated for Android and the device ABI. Unknown expressions fail with a message rather than selecting another platform's package.

Archives use HTTPS, mirror fallback, SHA-256 verification and validated byte-range resumption. ZIP and 7z archives are supported. Every extracted file must appear in the package manifest. Extracted files are not reread for individual SHA-256 checks; extraction relies on the verified archive and the extractor's built-in integrity checks. Paths cannot escape the installation, archive links are refused, and downloadable native/JVM executable files are refused. Game scripts are treated as game data, never loaded as Android classes or native libraries.

A new immutable content graph is prepared next to the active installation. Only after all packages and dependencies succeed does installed.json atomically activate the complete graph. A failed update preserves the prior installation. Unchanged packages can reuse the installed mod folder; completed verified archives are retained during an interrupted transaction so resuming does not repeat completed downloads. Downloads and obsolete content are removed after a successful activation. Allow space for old and new content plus download archives during installation.

The runtime working directory is Android/data/{applicationId}/files/game. -mod search paths follow Nebula mod_flag order, selected development package folders when applicable and resolved dependencies. Pilot/config files use FSO's per-application SDL preference storage; content cleanup only removes immutable content folders. Game and installer processes hold the same filesystem lock so content is never replaced while FSO is running.

Update an engine by replacing that version's jniLibs through the import script, incrementing versionCode/versionName and distributing a new signed APK or Play update. The application never downloads or self-updates executable engine blobs.

## Touch controls

The shared overlay is copied from FSO Android Wrapper, also used by Knossos.NET. It includes the radial weapon, target and communications controls, mouse flight pad, afterburner ring, top shortcuts, keyboard toggle and HUD visibility modes.

Each version packages src/main/assets/touch.json. Developers can remap bridge codes, replace individual radial actions or button bindings, and choose the initial HUD mode. There is no user remapping screen and the profile is never fetched from Nebula. See template/readme.md. Keep the stable Java namespace/JNI symbols unless rebuilding the bridge as well; applicationId remains independently configurable.

The launcher offers an ES / VK graphics selector, with Vulkan selected by default, followed by a Show FPS checkbox (off by default). Preferences are saved separately for each installed TC. The selected graphics backend adds exactly one of -opengl or -vulkan, and Show FPS adds -fps only when checked. These controls override conflicting copies of those switches in game.json. Vulkan is an optional device capability in the manifest so ES-only devices can install the application.

The footer displays Launcher Version: {shared version} / {TC version}. Edit shared/launcher-version.properties to change the common launcher version. Edit versionName in a TC's versions/{TC}/version.json for its manually maintained launcher variant version. versionCode remains the Android update counter and must increase for distributed APK updates.

Engine Version is queried from the bundled native engine on application startup using -get_flags json_v2. A separate :flags SDL activity receives the engine's setFlagsJson JNI callback and returns version.full. The displayed value is never inferred from filenames or engine-provenance.json. A failed query displays Unavailable; a stalled request ends after 30 seconds. This mechanism is adapted from Knossos.NET's FlagsActivity and AndroidHelper.
