# Add a standalone TC

1. From the project root, create a module:
       python scripts/new-version.py MyTC com.fsolauncher.mytc NebulaModID "My Game"
2. In versions/MyTC/version.json, set applicationId, displayName, versionCode and versionName. Every installed TC needs a different applicationId. Use a stable ID and increment versionCode on every APK update. versionName is also the manually maintained TC launcher version shown after the shared launcher version in the footer. The shared version is maintained in shared/launcher-version.properties. The importer sets abis automatically from the native source folders.
3. In src/main/assets/game.json, set the Nebula ID and contentRange. Use "*" for the latest stable content, ">=1.0.0" for a minimum, or "=1.0.0" to pin. Choose required/recommended package behavior with includeRecommended. The arguments array contains developer-controlled FSO arguments; Nebula cmdline strings are not executed. The launcher appends -mod itself. The ES/VK selector and Show FPS checkbox own -opengl, -vulkan and -fps; leave those switches out of this array. Vulkan is the initial default and Show FPS starts unchecked. Settings are saved per installed application.
4. Replace src/main/res/drawable/ic_foreground.xml and, optionally, add src/main/assets/background.png. Keep the adaptive icon wrapper or replace it with your own icon resources.
5. Import the native engine specifically for this TC:
       python scripts/import-engine.py MyTC D:/path/to/jniLibs
   The importer discovers all supported ABI subfolders in the source: arm64-v8a, armeabi-v7a, x86_64 and x86. Each must contain libSDL3.so, one release libfs2_open*.so and its shared dependencies. It imports every detected ABI, updates version.json's abis list so Gradle packages them, and regenerates engine-provenance.json with per-ABI hashes. DEBUG libraries are excluded unless explicitly selected as the engine. Use --engine to choose an exact filename when more than one is available, or --abis arm64-v8a x86_64 to import only a subset. The chosen engine is packaged as libfso.so. Keep the source outside this module's jniLibs and match SDL Java/header/native versions. Updating the imported binaries updates only this TC when its APK is rebuilt.
6. Configure production signing as described in the root readme, or generate a development key for testing.
7. Sync Gradle in Android Studio and select MyTC, or run:
       .\gradlew.bat :MyTC:assembleRelease
   With signing configured, the output is versions/MyTC/build/outputs/apk/release/com.fsolauncher.mytc-signed.apk. The root releaseApks task collects all configured versions in dist/.
8. Check dependencies, download/install, offline play, interrupted updates and controls on physical devices. Verify content and artwork distribution rights before publication.

## Community links

The template's game.json contains communityLinks: []. Leave it empty or omit it when the TC has no community links. Add entries with label and url to show centered buttons, one below another, above Launcher Version and Engine Version:

    "communityLinks": [
      {"label": "Website", "url": "https://example.com/"},
      {"label": "Discord", "url": "https://discord.com/invite/example", "icon": "discord"}
    ]

icon is optional. Built-in values are web, discord, github, wiki and forum. A custom drawable added under this module's src/main/res/drawable can also be referenced by its resource name. An optional description supplies a longer accessibility label and tooltip.

The small shared Hard-Light / FSO / Launcher row is configured once in shared/community-links.json and appears below Engine Version in every module. Keep its labels short; the entire row is limited to 45% of the viewport width. Set its links array to [] to hide the shared row. Changes are packaged at build time.

## Packaged touch profiles

The complete shared HUD is defined in shared/touch-default.json. The template's src/main/assets/touch.json inherits it with {"schemaVersion": 1}. Copy the shared JSON into this module's touch.json to define a complete custom layout. Its elements array sets the bars, buttons, wheels and joystick, with their positions, sizes, modes, labels and key codes. The mode button is fixed and always available.

See [the touch layout reference](../shared/touch-layout.md) for the schema and a complete example, including weighted center/outer actions and a joystick without an outer button. These files are compiled into the APK.

Legacy remapping profiles remain supported. For example:

Example touch.json:
    {
      "initialHudMode": 3,
      "codeMap": {
        "21": [21],
        "20": [20],
        "40": [40]
      },
      "actions": {
        "PRIMARY": {
          "label": "LASERS",
          "description": "Fire lasers",
          "codes": [21]
        },
        "BOTH": {
          "label": "BOTH",
          "codes": [21, 20]
        }
      },
      "buttons": {
        "btnAltJ": {
          "label": "Jump",
          "codes": [11, 12]
        }
      }
    }

initialHudMode ranges from 0 (mode switch only) to 3 (all controls). The mode switch remains available to players. codeMap replaces a shared bridge code everywhere it is used by configurable controls. An empty array disables that code. actions keys are the original radial label, including any escaped newline, or an explicit action ID. buttons keys are logical JSON IDs such as btnF1, btnEsc or btnAltJ. Explicit action/button codes override codeMap. These codes are held/released together for chords.

Common bridge codes:
| Code | Meaning |
| --- | --- |
| 1 | Escape |
| 2 | F3 |
| 7 / 8 / 9 | F1 / F2 / F4 |
| 10 | 0 |
| 11 / 12 | Left Alt / J |
| 20 / 21 | Space / Left Ctrl |
| 22 / 23 | Cycle primary / secondary |
| 30 / 31 / 32 | Y / H / B |
| 33 / 34 / 35 / 36 | E / F / T / S |
| 40 | Tab |
| 41 / 42 | Equals / Minus |
| 43 / 44 / 45 / 46 / 47 | Q / X / M / A / Z |
| 48 / 49 | Backslash / Backspace |
| 50 | C |
| 51 through 59 | 1 through 9 |
| 2001 / 2002 / 2003 | Left / middle / right mouse button |

For any other keyboard key, use 1000 + its SDL3 scancode. For example, SDL_SCANCODE_W is 26, so code 1026 presses W. Definitions are in shared/src/main/cpp/third_party/SDL3/SDL_scancode.h. Explicit chords can combine these codes with the standard bridge codes. Physical keyboard input and FSO's own control bindings continue to use the engine's input system.

No code should be downloaded to change engine or touch behavior. Rebuild and distribute the APK for those changes.
