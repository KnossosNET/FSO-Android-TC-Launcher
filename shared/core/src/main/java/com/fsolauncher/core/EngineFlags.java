package com.fsolauncher.core;

import com.google.gson.*;
import java.io.IOException;

/** Reads the engine's reported version without guessing from its filename or metadata. */
public final class EngineFlags {
    private EngineFlags() {}
    public static String fullVersion(String output) throws IOException {
        if (output == null) throw new IOException("The engine returned no flags JSON");
        // Desktop diagnostic captures can include a console prompt around the JSON.
        int first = output.indexOf('{'), last = output.lastIndexOf('}');
        if (first < 0 || last <= first) throw new IOException("The engine returned no JSON object");
        try {
            JsonObject root = JsonParser.parseString(output.substring(first, last + 1)).getAsJsonObject();
            JsonObject version = root.getAsJsonObject("version");
            JsonElement full = version == null ? null : version.get("full");
            if (full == null || !full.isJsonPrimitive() || !full.getAsJsonPrimitive().isString()
                    || full.getAsString().trim().isEmpty()) throw new IOException("Missing engine version.full");
            return full.getAsString();
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new IOException("Invalid engine flags JSON", e);
        }
    }
}
