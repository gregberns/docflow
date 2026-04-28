package com.docflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract test for {@code config/forbidden-strings.txt} and the parsing rules the {@code
 * grepForbiddenStrings} Gradle task (C7.6) applies to it.
 *
 * <p>The file is split into two sections: a default section of quoted-literal patterns and a {@code
 * [bare-tokens]} section of substring patterns (env-read expressions per C7-R13). The Gradle task
 * strips comments and blank lines, routes lines into the active section, and fails fast on a
 * missing file or a fully empty post-strip list.
 */
class GrepForbiddenStringsFileTest {

  private static final String BARE_TOKENS_HEADER = "[bare-tokens]";

  @Test
  void canonicalFileExistsAndContainsCategorySectionHeaders() throws IOException {
    Path file = canonicalForbiddenStringsFile();
    String text = Files.readString(file, StandardCharsets.UTF_8);

    assertThat(text)
        .as("config/forbidden-strings.txt must call out the categories the C7.6 task understands")
        .contains("# Stage names")
        .contains("# Client slugs")
        .contains("# Client display-name variants")
        .contains("# Env-file literal")
        .contains(BARE_TOKENS_HEADER);
  }

  @Test
  void parserSplitsLiteralsAndBareTokensIntoSeparateBuckets() throws IOException {
    Path file = canonicalForbiddenStringsFile();

    Sections sections = parseSections(file);

    assertThat(sections.literals())
        .as("11 stage names + 3 client slugs + 3 display variants + .env = 18 literals")
        .hasSize(18)
        .contains(
            "Review",
            "Manager Approval",
            "Filed",
            "Rejected",
            "riverside-bistro",
            "pinnacle-legal",
            "ironworks-construction",
            "Riverside Bistro",
            "Pinnacle Legal Group",
            "Ironworks Construction",
            ".env");
    assertThat(sections.bareTokens())
        .as("env-read expressions per C7-R13")
        .containsExactlyInAnyOrder("System.getenv", "@Value");
  }

  @Test
  void commentsOnlyFileYieldsZeroPatternsTriggeringFailFast(@TempDir Path tmp) throws IOException {
    Path commentsOnly = tmp.resolve("forbidden-strings.txt");
    Files.writeString(
        commentsOnly,
        "# only comments here\n\n# even more comments\n   \n",
        StandardCharsets.UTF_8);

    Sections sections = parseSections(commentsOnly);

    assertThat(sections.literals()).isEmpty();
    assertThat(sections.bareTokens()).isEmpty();
  }

  @Test
  void missingFileSurfacesAsNoSuchFileException(@TempDir Path tmp) {
    Path missing = tmp.resolve("does-not-exist.txt");

    assertThatThrownBy(() -> parseSections(missing))
        .as("the parser must surface a missing file rather than silently returning empty")
        .isInstanceOf(NoSuchFileException.class);
  }

  private static Sections parseSections(Path file) throws IOException {
    List<String> literals = new java.util.ArrayList<>();
    List<String> bareTokens = new java.util.ArrayList<>();
    List<String> current = literals;
    for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      if (line.startsWith("[") && line.endsWith("]")) {
        if (BARE_TOKENS_HEADER.equals(line)) {
          current = bareTokens;
          continue;
        }
        throw new IllegalStateException("Unknown section header " + line);
      }
      current.add(line);
    }
    return new Sections(List.copyOf(literals), List.copyOf(bareTokens));
  }

  private record Sections(List<String> literals, List<String> bareTokens) {}

  private static Path canonicalForbiddenStringsFile() {
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null) {
      Path candidate = cursor.resolve("config/forbidden-strings.txt");
      if (Files.exists(candidate)) {
        return candidate;
      }
      cursor = cursor.getParent();
    }
    throw new IllegalStateException(
        "Unable to locate config/forbidden-strings.txt walking up from "
            + Path.of("").toAbsolutePath());
  }
}
