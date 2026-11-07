# Packaged touch layouts

The complete default HUD is defined in `shared/touch-default.json`. Gradle packages it as `shared-touch-default.json` in every APK. Edit this file to change the shared layout. Each TC can replace it with its own `versions/<module>/src/main/assets/touch.json` containing an `elements` array. Rebuild the APK after editing either file.

The mode button is created by the launcher at the top center. It cycles through modes 0, 1, 2 and 3. Its position, label, input and visibility are owned by the launcher. The ID `btnToggle` is reserved.

## Choosing the shared or TC layout

A module profile containing only `{"schemaVersion": 1}` inherits the complete shared layout. Omitting the module file has the same result. FS2 Demo, Wing Commander Saga and the template use this form. FotG provides a complete custom elements array.

To make a custom layout, copy `shared/touch-default.json` into the module's `touch.json`, then edit its `elements`. An explicit `elements` array replaces all shared elements. An empty array creates a HUD containing only the fixed mode button.

Legacy profiles containing `initialHudMode`, `codeMap`, `actions` and `buttons` still work. When `elements` is absent they remap the shared layout. In a legacy `actions` map, the lookup key is the original radial label, including escaped newlines, or the action's optional `id`. Keys in `buttons` are the logical button IDs such as `btnF1` and `btnAltJ`. Explicit binding `codes` take precedence over `codeMap`. An empty `codes` array disables that action's input while retaining its visual segment.

An invalid module profile logs an `FSOTouch` warning and loads the shared layout. The core tests validate the shared file and every module's profile before release builds in CI.

## Root fields and geometry

| Field | Meaning |
| --- | --- |
| `schemaVersion` | Currently `1` |
| `initialHudMode` | Initial mode, from `0` through `3`. Defaults to `3` |
| `elements` | Ordered array of controls. Later entries draw above earlier ones |
| `id` | Unique element ID. Bar button IDs must also be unique |
| `type` | `buttonBar`, `button`, `radial` or `joystick` |
| `description` | Optional accessibility description |
| `modes` | Modes where the element is visible. Defaults to `[1,2,3]` |
| `position.x`, `position.y` | Bias from `0` to `1` within the available space after sizing. `0` aligns left/top, `0.5` centers and `1` aligns right/bottom |
| `position.leftDp`, `topDp`, `rightDp`, `bottomDp` | Edge margins in dp. Defaults to zero |
| `size.heightDp` or `heightPercent` | Fixed height in dp, or fraction of the safe viewport height |
| `size.widthDp` or `widthPercent` | Width of a bar or standalone button. Radials and joysticks use a square derived from their height |
| `size.maxHeightDp`, `maxWidthDp` | Optional size caps |
| `size.heightOffsetDp`, `widthOffsetDp` | Optional adjustments to the calculated size |

Insets from display cutouts and system gestures are applied before calculating the viewport. Controls are constrained to that area. For example, reserve 32 dp above a wheel with `position.topDp: 32` to leave room for a top bar.

## Actions and key codes

Every action uses `label` and a `codes` array. Add `description` for accessibility. Codes in one array form a held chord. Put modifiers first, for example `[1226,1008]` for Left Alt + E. The launcher releases the chord in reverse order and keeps keys held when another finger or neighboring radial action still needs them.

Use the existing bridge codes listed in `template/readme.md`, or `1000 + SDL3 scancode` for another keyboard key. For example, `1040` sends Enter, `1008` sends E and `1051` sends the semicolon key. Mouse buttons use `2001` for left, `2002` for middle and `2003` for right. They can be held and combined with keyboard codes in the same action. The joystick emits relative mouse movement while preserving held touch mouse buttons.

An action can have an optional logical `id` for legacy overrides. The `weight` field defaults to `1`. In radial centers it controls the relative height of the horizontal bands. For outer actions it controls the angular share of the ring. Labels support escaped newlines.

## Element types

### Button bars

A `buttonBar` has a `buttons` array and `align` set to `start` or `end`. It scrolls horizontally if its buttons exceed the available width. Buttons accept optional `modes`, `minWidthDp`, `heightDp` and `textSp`. The default button height is 28 dp and text size is 10 sp.

Use `"command": "keyboard"` instead of `codes` for a button that opens/closes the SDL software keyboard. This command is supported by bar buttons and standalone buttons.

The shared layout uses two bars on either side of the fixed mode button. Each bar is half the viewport width minus 19 dp, reserving the mode button's 38 dp width.

### Standalone buttons

A `button` has an `action` object, plus the usual size, position and modes. Its touch target fills the configured rectangle.

### Radial wheels

A `radial` has a `center` array containing one, two or three actions, and an `outer` array with at least one action. The array lengths determine the number of buttons. Each action can have a different `weight`.

`innerRadiusRatio` determines the center radius relative to the full wheel, from `0.38` to `0.72`. `firstOuterActionAngle` sets the center angle of the first outer action. The default is `-90` degrees, at the top, with following actions clockwise. `labelScale` ranges from `0.7` to `1.4`. Long outer labels are reduced to fit their sectors; use `\n` for explicit line breaks.

### Joystick and optional outer button

A layout supports one relative-mouse `joystick`. `deadzone` ranges from `0` to `0.5` and defaults to `0.12`.

Add `ringAction` to create the large outer button, with its own label and codes. The joystick and ring can be used simultaneously. Omit `ringAction`, or set it to `null`, to remove the outer button and use the full circular area for steering.

## Small custom layout example

```json
{
  "schemaVersion": 1,
  "initialHudMode": 3,
  "elements": [
    {
      "id": "steering",
      "type": "joystick",
      "modes": [2, 3],
      "position": {"x": 0, "y": 1, "topDp": 32},
      "size": {"heightPercent": 0.5, "maxHeightDp": 240}
    },
    {
      "id": "weapons",
      "type": "radial",
      "modes": [3],
      "position": {"x": 1, "y": 1, "topDp": 32},
      "size": {"heightPercent": 0.5, "maxHeightDp": 240},
      "center": [
        {"label": "PRIMARY", "codes": [21], "weight": 2},
        {"label": "BOTH", "codes": [21, 20], "weight": 1},
        {"label": "SECONDARY", "codes": [20], "weight": 2}
      ],
      "outer": [
        {"label": "NEXT P", "codes": [22], "weight": 2},
        {"label": "NEXT S", "codes": [23]},
        {"label": "MATCH", "codes": [45]}
      ]
    },
    {
      "id": "exitMenu",
      "type": "button",
      "modes": [1, 2, 3],
      "position": {"x": 1, "y": 0},
      "size": {"widthDp": 64, "heightDp": 28},
      "action": {"label": "ESC", "codes": [1]}
    }
  ]
}
```

The example uses the standard engine keyboard bindings. TC-specific key assignments can be configured in the same structure.

## FotG profile

`versions/Fotg/src/main/assets/touch.json` contains the FotG-specific layout. Communications retain the shared configuration. Targeting uses one Space center and a larger H sector at the bottom. Weapons retain the shared wheel size, with left mouse button (`2001`) for primary fire and Tab (`40`) for secondary fire. The joystick has no afterburner ring. The left top bar contains `0, F1, F2, F3, F4, ESC, KEYB`; the right bar contains `ALT+E, ALT+Q, ENTER, ;, A, D`.

The displayed `}` action sends bridge code `48` (SDL backslash) for maximum throttle. FSO's legacy key names map that scancode to `KEY_SLASH`, which FotG assigns to `MAX_THROTTLE` in its Controlconfigdefaults.tbl. Bridge code `23` sends a different scancode and is not the maximum-throttle binding.
