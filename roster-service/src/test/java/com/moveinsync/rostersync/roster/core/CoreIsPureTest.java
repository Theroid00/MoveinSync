package com.moveinsync.rostersync.roster.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** The core algorithm must be testable without a web server or database (spec §0.8). */
class CoreIsPureTest {

    static final Pattern FORBIDDEN = Pattern.compile(
            "^\\s*import\\s+(static\\s+)?(org\\.springframework\\.|java\\.sql\\.|javax\\.sql\\.|jakarta\\.)");

    @Test
    void coreSourcesImportNoFrameworkOrJdbc() throws IOException {
        Path main = Path.of("src", "main", "java");
        List<Path> coreFiles;
        try (Stream<Path> files = Files.walk(main)) {
            coreFiles = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/core/") || p.toString().contains("\\core\\"))
                    .toList();
        }
        assertThat(coreFiles).as("core sources found").hasSizeGreaterThan(10);

        List<String> violations = new ArrayList<>();
        for (Path file : coreFiles) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (FORBIDDEN.matcher(lines.get(i)).find()) {
                    violations.add(file + ":" + (i + 1) + " " + lines.get(i).trim());
                }
            }
        }
        assertThat(violations).as("forbidden imports in core").isEmpty();
    }
}
