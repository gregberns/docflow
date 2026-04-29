package com.docflow.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Test-time sanity sweep for C4-R10: zero literal stage strings or client slugs in {@code
 * com.docflow.workflow} or {@code com.docflow.document}.
 *
 * <p>The Gradle {@code grepForbiddenStrings} task (C7-R5) is the source of truth and the gate that
 * fails CI; this test gives faster feedback during a normal {@code ./gradlew test} run and pins the
 * scope explicitly to the two C4 packages.
 */
class EngineForbiddenStringsTest {

  private static final String BARE_TOKENS_HEADER = "[bare-tokens]";

  @Test
  void c4SourcePackagesContainNoForbiddenLiterals() throws IOException {
    List<String> literals = readForbiddenLiterals();
    assertThat(literals)
        .as("forbidden-strings.txt must enumerate stage names and client slugs")
        .isNotEmpty();

    Path mainJava = mainJavaRoot();
    List<Path> sources = new ArrayList<>();
    for (String pkg : List.of("com/docflow/workflow", "com/docflow/document")) {
      Path pkgRoot = mainJava.resolve(pkg);
      if (!Files.exists(pkgRoot)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(pkgRoot)) {
        walk.filter(Files::isRegularFile)
            .filter(p -> p.getFileName().toString().endsWith(".java"))
            .forEach(sources::add);
      }
    }

    assertThat(sources)
        .as("expected at least one Java source under com.docflow.workflow / com.docflow.document")
        .isNotEmpty();

    List<String> violations = new ArrayList<>();
    for (Path source : sources) {
      List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        for (String literal : literals) {
          String quoted = "\"" + literal + "\"";
          if (line.contains(quoted)) {
            violations.add(source + ":" + (i + 1) + ": forbidden literal " + quoted);
          }
        }
      }
    }

    assertThat(violations)
        .as("C4-R10: zero literal stage strings or client slugs in C4 source packages")
        .isEmpty();
  }

  private static List<String> readForbiddenLiterals() throws IOException {
    Path file = repoRoot().resolve("config/forbidden-strings.txt");
    List<String> out = new ArrayList<>();
    for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      if (line.equals(BARE_TOKENS_HEADER)) {
        break;
      }
      if (line.startsWith("[") && line.endsWith("]")) {
        break;
      }
      out.add(line);
    }
    return out;
  }

  private static Path mainJavaRoot() {
    Path candidate = repoRoot().resolve("backend/src/main/java");
    if (!Files.exists(candidate)) {
      throw new IllegalStateException("Unable to locate backend/src/main/java at " + candidate);
    }
    return candidate;
  }

  private static Path repoRoot() {
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null) {
      if (Files.exists(cursor.resolve("config/forbidden-strings.txt"))) {
        return cursor;
      }
      cursor = cursor.getParent();
    }
    throw new IllegalStateException(
        "Unable to locate config/forbidden-strings.txt walking up from "
            + Path.of("").toAbsolutePath());
  }
}
