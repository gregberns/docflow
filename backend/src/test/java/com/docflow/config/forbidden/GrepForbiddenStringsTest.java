package com.docflow.config.forbidden;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GrepForbiddenStringsTest {

  @Test
  void forbiddenStringsFileEnumeratesElevenStagesAndThreeClients() throws IOException {
    List<String> literals = readForbiddenLiterals();

    assertThat(literals)
        .as("forbidden-strings.txt literals: 11 stage names + 3 slugs + 3 display variants + .env")
        .hasSize(18);

    assertThat(literals)
        .contains(
            "Review",
            "Manager Approval",
            "Finance Approval",
            "Attorney Approval",
            "Billing Approval",
            "Partner Approval",
            "Project Manager Approval",
            "Accounting Approval",
            "Client Approval",
            "Filed",
            "Rejected",
            "riverside-bistro",
            "pinnacle-legal",
            "ironworks-construction",
            "Riverside Bistro",
            "Pinnacle Legal Group",
            "Ironworks Construction",
            ".env");
  }

  @Test
  void literalViolationDetectedWithFileAndLine() throws IOException, InterruptedException {
    Path bad = fixture("Bad.java");

    GrepResult result = grepQuotedLiteral("Manager Approval", bad);

    assertThat(result.matched())
        .as("grep must flag the raw \"Manager Approval\" literal in %s", bad)
        .isTrue();
    assertThat(result.output())
        .as("failure report must surface file path and line number")
        .contains(bad.toString())
        .containsPattern(":\\d+:");
  }

  @Test
  void enumReferencePassesWithNoViolation() throws IOException, InterruptedException {
    Path good = fixture("Good.java");

    for (String literal : readForbiddenLiterals()) {
      GrepResult result = grepQuotedLiteral(literal, good);
      assertThat(result.matched())
          .as(
              "control fixture %s must not trigger forbidden literal %s; grep output: %s",
              good, literal, result.output())
          .isFalse();
    }
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

  private static Path fixture(String name) {
    Path candidate = repoRoot().resolve("backend/src/test/resources/grep-fixtures").resolve(name);
    if (!Files.exists(candidate)) {
      throw new IllegalStateException("Missing grep fixture: " + candidate);
    }
    return candidate;
  }

  private static List<String> readForbiddenLiterals() throws IOException {
    Path file = repoRoot().resolve("config/forbidden-strings.txt");
    List<String> out = new ArrayList<>();
    for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      if (line.startsWith("[") && line.endsWith("]")) {
        break;
      }
      out.add(line);
    }
    return out;
  }

  private static GrepResult grepQuotedLiteral(String literal, Path target)
      throws IOException, InterruptedException {
    String pattern = "\"" + literal + "\"";
    ProcessBuilder pb =
        new ProcessBuilder("grep", "-H", "-n", "-F", "--", pattern, target.toString())
            .redirectErrorStream(true);
    Process process = pb.start();

    StringBuilder captured = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        captured.append(line).append('\n');
      }
    }

    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("grep timed out for pattern " + pattern);
    }

    int exit = process.exitValue();
    if (exit != 0 && exit != 1) {
      throw new IllegalStateException(
          "grep exited with status " + exit + " for pattern " + pattern + ": " + captured);
    }
    return new GrepResult(exit == 0, captured.toString());
  }

  private record GrepResult(boolean matched, String output) {}
}
