package com.mystipixel.royalsacks.message;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageKeysTest {

    // messages.send(x, "key" ...), sendPlain(x, "key" ...), format("key" ...), and "a" : "b" choices
    private static final Pattern CALL = Pattern.compile("messages\\.(?:send|sendPlain|format)\\([^\"]*\"([a-z-]+)\"(?:\\s*:\\s*\"([a-z-]+)\")?");

    @Test
    void everyMessageTheCodeSendsIsInTheBundledFile() throws IOException {
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/messages.yml").toFile());
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = CALL.matcher(Files.readString(file));
                while (m.find()) {
                    for (int g = 1; g <= 2; g++) {
                        String key = m.group(g);
                        if (key != null && !bundled.contains(key)) {
                            missing.add(file.getFileName() + ": " + key);
                        }
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "messages.yml lacks " + missing);
    }
}
