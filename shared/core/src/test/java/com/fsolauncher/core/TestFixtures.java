package com.fsolauncher.core;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class TestFixtures {
    private TestFixtures() {}

    static String read(String name) throws IOException {
        String resource = "/fixtures/" + name;
        try (InputStream input = TestFixtures.class.getResourceAsStream(resource)) {
            if (input == null) throw new FileNotFoundException("Missing test resource: " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
