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
 * <p>The Gradle task strips {@code #}-prefixed comments and blank lines and treats every remaining
 * line as a forbidden literal. It fails fast if the file is missing/unreadable or the post-strip
 * list is empty. This test exercises that parsing contract independently of the Gradle runtime so a
 * regression in either the file or the parser shape is caught at unit-test speed.
 *
 * <p>The fixture-driven positive/negative regex test owned by C1.9 lives at {@code
 * com.docflow.config.forbidden.GrepForbiddenStringsTest}. A future refinement (deferred) is a
 * Gradle TestKit run that drives the {@code grepForbiddenStrings} task end-to-end against synthetic
 * source trees; this contract test stops short of that.
 */
class GrepForbiddenStringsFileTest {

  @Test
  void canonicalFileExistsAndContainsCategorySectionHeaders() throws IOException {
    Path file = canonicalForbiddenStringsFile();
    String text = Files.readString(file, StandardCharsets.UTF_8);

    assertThat(text)
        .as("config/forbidden-strings.txt must call out the categories the C7.6 task understands")
        .contains("# Stage names")
        .contains("# Client slugs")
        .contains("# Client display-name variants");
  }

  @Test
  void parserStripsCommentsAndBlanksAndYieldsCanonicalLiterals() throws IOException {
    Path file = canonicalForbiddenStringsFile();

    List<String> patterns = parsePatterns(file);

    assertThat(patterns)
        .as("11 stage names + 3 client slugs + 3 display variants must remain after strip")
        .hasSize(17);
    assertThat(patterns)
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
            "Ironworks Construction");
  }

  @Test
  void commentsOnlyFileYieldsZeroPatternsTriggeringFailFast(@TempDir Path tmp) throws IOException {
    Path commentsOnly = tmp.resolve("forbidden-strings.txt");
    Files.writeString(
        commentsOnly,
        "# only comments here\n\n# even more comments\n   \n",
        StandardCharsets.UTF_8);

    List<String> patterns = parsePatterns(commentsOnly);

    assertThat(patterns)
        .as("a comments/blanks-only file must parse to zero patterns so the task can fail fast")
        .isEmpty();
  }

  @Test
  void missingFileSurfacesAsNoSuchFileException(@TempDir Path tmp) {
    Path missing = tmp.resolve("does-not-exist.txt");

    assertThatThrownBy(() -> parsePatterns(missing))
        .as("the parser must surface a missing file rather than silently returning empty")
        .isInstanceOf(NoSuchFileException.class);
  }

  private static List<String> parsePatterns(Path file) throws IOException {
    return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
        .map(String::strip)
        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
        .toList();
  }

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
