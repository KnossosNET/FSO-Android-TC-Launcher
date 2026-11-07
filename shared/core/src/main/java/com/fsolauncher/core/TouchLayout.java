package com.fsolauncher.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Packaged touch layout, independent of Android so profiles can be validated on a desktop. */
public final class TouchLayout {
    public int schemaVersion = 1;
    public int initialHudMode = 3;
    public List<Element> elements = new ArrayList<>();
    public Map<String, int[]> codeMap = new LinkedHashMap<>();
    public Map<String, Action> actions = new LinkedHashMap<>();
    public Map<String, Action> buttons = new LinkedHashMap<>();

    public static final class Action {
        public String id, label, description, command;
        public int[] codes, modes;
        public float weight = 1f;
        public float minWidthDp;
        public float heightDp = 28f;
        public float textSp = 10f;
    }

    public static final class Position {
        public float x, y;
        public float leftDp, topDp, rightDp, bottomDp;
    }

    public static final class Size {
        public float widthDp, heightDp, widthPercent, heightPercent;
        public float widthOffsetDp, heightOffsetDp, maxWidthDp, maxHeightDp;
    }

    public static final class Element {
        public String id, type, description;
        public int[] modes = {1, 2, 3};
        public Position position = new Position();
        public Size size = new Size();
        public String align = "start";
        public List<Action> buttons = new ArrayList<>();
        public List<Action> center = new ArrayList<>();
        public List<Action> outer = new ArrayList<>();
        public Action action, ringAction;
        public float innerRadiusRatio = .56f;
        public float firstOuterActionAngle = -90f;
        public float labelScale = 1f;
        public float deadzone = .12f;
    }

    public static final class Bounds {
        public final int x, y, width, height;
        Bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    public static TouchLayout parse(String sharedJson, String moduleJson) {
        JsonObject shared = JsonParser.parseString(sharedJson).getAsJsonObject();
        JsonObject chosen = shared.deepCopy();
        if (moduleJson != null) {
            JsonObject module = JsonParser.parseString(moduleJson).getAsJsonObject();
            if (module.has("elements")) {
                chosen = module;
            } else {
                for (String field : List.of("schemaVersion", "initialHudMode", "codeMap", "actions", "buttons")) {
                    if (module.has(field)) chosen.add(field, module.get(field));
                }
            }
        }
        TouchLayout layout = new Gson().fromJson(chosen, TouchLayout.class);
        layout.validate();
        return layout;
    }

    public Action resolve(Action source, boolean button) {
        Map<String, Action> overrides = button ? buttons : actions;
        Action override = overrides.get(source.id);
        if (override == null && !button) override = overrides.get(source.label);
        Action resolved = new Action();
        resolved.id = source.id;
        resolved.label = override != null && override.label != null ? override.label : source.label;
        resolved.description = override != null && override.description != null ? override.description : source.description;
        resolved.command = source.command;
        resolved.weight = source.weight;
        resolved.modes = source.modes;
        resolved.minWidthDp = source.minWidthDp;
        resolved.heightDp = source.heightDp;
        resolved.textSp = source.textSp;
        if (override != null && override.codes != null) {
            resolved.codes = override.codes.clone();
        } else {
            List<Integer> mapped = new ArrayList<>();
            if (source.codes != null) for (int code : source.codes) {
                int[] replacements = codeMap.get(Integer.toString(code));
                if (replacements == null) mapped.add(code);
                else for (int replacement : replacements) mapped.add(replacement);
            }
            resolved.codes = mapped.stream().mapToInt(Integer::intValue).toArray();
        }
        return resolved;
    }

    public static boolean visible(int[] modes, int mode) {
        for (int candidate : modes) if (candidate == mode) return true;
        return false;
    }

    /** x/y are biases in the space left after sizing and applying edge margins. */
    public static Bounds bounds(Element element, int viewportWidth, int viewportHeight, float density) {
        Size size = element.size;
        Position position = element.position;
        float availableWidth = Math.max(0, viewportWidth - (position.leftDp + position.rightDp) * density);
        float availableHeight = Math.max(0, viewportHeight - (position.topDp + position.bottomDp) * density);
        float height = size.heightDp > 0 ? size.heightDp * density : viewportHeight * size.heightPercent;
        height += size.heightOffsetDp * density;
        if (size.maxHeightDp > 0) height = Math.min(height, size.maxHeightDp * density);
        height = Math.max(0, Math.min(height, availableHeight));
        float width = size.widthDp > 0 ? size.widthDp * density : viewportWidth * size.widthPercent;
        width += size.widthOffsetDp * density;
        if ("radial".equals(element.type) || "joystick".equals(element.type)) width = height;
        if (size.maxWidthDp > 0) width = Math.min(width, size.maxWidthDp * density);
        width = Math.max(0, Math.min(width, availableWidth));
        if ("radial".equals(element.type) || "joystick".equals(element.type)) height = width;
        int x = Math.round(position.leftDp * density + (availableWidth - width) * position.x);
        int y = Math.round(position.topDp * density + (availableHeight - height) * position.y);
        return new Bounds(x, y, Math.round(width), Math.round(height));
    }

    private void validate() {
        require(schemaVersion == 1, "Unsupported touch schemaVersion");
        require(initialHudMode >= 0 && initialHudMode <= 3, "initialHudMode must be 0..3");
        require(elements != null && codeMap != null && actions != null && buttons != null, "Null touch configuration");
        require(!buttons.containsKey("btnToggle"), "The mode button is not configurable");
        Set<String> ids = new HashSet<>();
        ids.add("btnToggle");
        int joysticks = 0;
        for (Element element : elements) {
            require(element != null, "Null element");
            identifier(element.id, ids);
            modes(element.modes);
            require(element.position != null && element.size != null, "Missing element geometry");
            Position p = element.position;
            range(p.x, 0, 1, "position.x");
            range(p.y, 0, 1, "position.y");
            for (float margin : new float[]{p.leftDp, p.topDp, p.rightDp, p.bottomDp}) range(margin, 0, 4096, "margin");
            Size s = element.size;
            range(s.widthDp, 0, 4096, "widthDp");
            range(s.heightDp, 0, 4096, "heightDp");
            range(s.widthPercent, 0, 1, "widthPercent");
            range(s.heightPercent, 0, 1, "heightPercent");
            range(s.maxWidthDp, 0, 4096, "maxWidthDp");
            range(s.maxHeightDp, 0, 4096, "maxHeightDp");
            range(s.widthOffsetDp, -4096, 4096, "widthOffsetDp");
            range(s.heightOffsetDp, -4096, 4096, "heightOffsetDp");
            require(s.heightDp > 0 || s.heightPercent > 0, "Element needs a height");
            switch (element.type == null ? "" : element.type) {
                case "buttonBar":
                    require(s.widthDp > 0 || s.widthPercent > 0, "Button bar needs a width");
                    require("start".equals(element.align) || "end".equals(element.align), "Invalid bar alignment");
                    require(element.buttons != null, "Null button list");
                    for (Action button : element.buttons) {
                        validateAction(button, true);
                        identifier(button.id, ids);
                    }
                    break;
                case "button":
                    require(s.widthDp > 0 || s.widthPercent > 0, "Button needs a width");
                    validateAction(element.action, true);
                    break;
                case "radial":
                    require(element.center != null && element.center.size() >= 1 && element.center.size() <= 3,
                        "Radial center needs 1..3 actions");
                    require(element.outer != null && !element.outer.isEmpty(), "Radial needs outer actions");
                    for (Action action : element.center) validateAction(action, false);
                    for (Action action : element.outer) validateAction(action, false);
                    range(element.innerRadiusRatio, .38f, .72f, "innerRadiusRatio");
                    range(element.firstOuterActionAngle, -360, 360, "firstOuterActionAngle");
                    range(element.labelScale, .7f, 1.4f, "labelScale");
                    break;
                case "joystick":
                    require(++joysticks <= 1, "A layout supports one mouse joystick");
                    range(element.deadzone, 0, .5f, "deadzone");
                    if (element.ringAction != null) validateAction(element.ringAction, false);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown touch element type: " + element.type);
            }
        }
        for (int[] codes : codeMap.values()) validateCodes(codes);
        for (Action action : actions.values()) validateOverride(action);
        for (Action action : buttons.values()) validateOverride(action);
    }

    private static void identifier(String id, Set<String> used) {
        require(id != null && !id.isBlank() && used.add(id), "Duplicate or reserved touch element ID: " + id);
    }

    private static void validateOverride(Action action) {
        require(action != null, "Null binding override");
        if (action.codes != null) validateCodes(action.codes);
    }

    private static void validateAction(Action action, boolean allowKeyboard) {
        require(action != null && action.label != null, "Action needs a label");
        require(action.command == null || (allowKeyboard && "keyboard".equals(action.command)), "Invalid action command");
        if (action.command == null) validateCodes(action.codes);
        range(action.weight, .001f, 1000, "weight");
        range(action.heightDp, 1, 4096, "button heightDp");
        range(action.minWidthDp, 0, 4096, "minWidthDp");
        range(action.textSp, 1, 96, "textSp");
        if (action.modes != null) modes(action.modes);
    }

    private static void validateCodes(int[] codes) {
        require(codes != null, "Action needs codes (use [] to disable its input)");
        for (int code : codes) {
            boolean known = (code >= 1 && code <= 12) || (code >= 20 && code <= 23)
                || (code >= 30 && code <= 36) || (code >= 40 && code <= 59)
                || (code >= 1001 && code < 1512) || (code >= 2001 && code <= 2003);
            require(known, "Unknown touch key code: " + code);
        }
    }

    private static void modes(int[] modes) {
        require(modes != null, "Null visibility modes");
        for (int mode : modes) require(mode >= 0 && mode <= 3, "Visibility mode must be 0..3");
    }

    private static void range(float value, float minimum, float maximum, String name) {
        require(Float.isFinite(value) && value >= minimum && value <= maximum, "Invalid " + name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
