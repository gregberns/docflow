package com.docflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docflow.ingestion.storage.StoredDocumentStorage;
import com.docflow.ingestion.storage.StoredFileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit boundary checks for C2 ingestion. Three concerns:
 *
 * <ol>
 *   <li>AC-R6: no DELETE HTTP route lives in {@code com.docflow.ingestion} or in any controller
 *       that targets the {@code documents} surface.
 *   <li>{@code com.docflow.ingestion.internal} is referenced only from inside {@code
 *       com.docflow.ingestion}.
 *   <li>AC-R7: {@link StoredDocumentStorage} is an interface and a hand-rolled in-memory impl
 *       satisfies the same contract — i.e. the seam is pluggable without touching production code.
 * </ol>
 */
class IngestionBoundaryTest {

  private static final Path MAIN_JAVA = locateMainJavaRoot();

  @Test
  void noDeleteMappingExistsForDocumentsAnywhereInTheIngestionOrApiPackages() throws IOException {
    Pattern deleteMapping = Pattern.compile("@DeleteMapping");
    Pattern requestDelete =
        Pattern.compile("RequestMethod\\s*\\.\\s*DELETE|method\\s*=\\s*RequestMethod\\.DELETE");

    List<Path> ingestionFiles = walkJava(MAIN_JAVA.resolve("com/docflow/ingestion"));
    List<Path> apiFiles = walkJava(MAIN_JAVA.resolve("com/docflow/api"));

    List<String> offenders = new ArrayList<>();
    for (Path file : concat(ingestionFiles, apiFiles)) {
      String body = Files.readString(file, StandardCharsets.UTF_8);
      if (deleteMapping.matcher(body).find() || requestDelete.matcher(body).find()) {
        offenders.add(MAIN_JAVA.relativize(file).toString());
      }
    }

    assertThat(offenders)
        .as(
            "AC-R6: no @DeleteMapping or RequestMethod.DELETE may exist in ingestion or api "
                + "packages — deletion is out of scope for C2.")
        .isEmpty();
  }

  @Test
  void ingestionInternalIsNeverReferencedFromOutsideTheIngestionPackage() throws IOException {
    Pattern internalRef = Pattern.compile("com\\.docflow\\.ingestion\\.internal");

    List<Path> allMainJava = walkJava(MAIN_JAVA);
    List<String> offenders = new ArrayList<>();
    for (Path file : allMainJava) {
      String relative = MAIN_JAVA.relativize(file).toString().replace('\\', '/');
      if (relative.startsWith("com/docflow/ingestion/")) {
        continue;
      }
      String body = Files.readString(file, StandardCharsets.UTF_8);
      if (internalRef.matcher(body).find()) {
        offenders.add(relative);
      }
    }

    assertThat(offenders)
        .as(
            "com.docflow.ingestion.internal is package-private API; only sources inside "
                + "com.docflow.ingestion may reference it.")
        .isEmpty();
  }

  @Test
  void storedDocumentStorageIsAnInterfaceWithExactlyOneProductionImplementation()
      throws IOException {
    assertThat(StoredDocumentStorage.class.isInterface())
        .as("StoredDocumentStorage must be an interface to satisfy AC-R7's pluggability claim")
        .isTrue();

    Path storageDir = MAIN_JAVA.resolve("com/docflow/ingestion/storage");
    List<Path> impls;
    try (Stream<Path> stream = Files.walk(storageDir)) {
      impls =
          stream
              .filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().endsWith(".java"))
              .filter(IngestionBoundaryTest::declaresStorageImplementation)
              .toList();
    }

    assertThat(impls)
        .as(
            "AC-R7: exactly one production StoredDocumentStorage implementation lives in "
                + "com.docflow.ingestion.storage")
        .hasSize(1);
  }

  @Test
  void inMemoryStorageImplementationIsPluggableWithoutChangingProductionCode() {
    StoredDocumentStorage storage = new InMemoryStoredDocumentStorage();
    StoredDocumentId id = StoredDocumentId.generate();
    byte[] payload = "boundary".getBytes(StandardCharsets.UTF_8);

    storage.save(id, payload);
    assertThat(storage.load(id)).isEqualTo(payload);

    StoredDocumentId missing = StoredDocumentId.generate();
    assertThatThrownBy(() -> storage.load(missing)).isInstanceOf(StoredFileNotFoundException.class);

    storage.delete(id);
    assertThatThrownBy(() -> storage.load(id)).isInstanceOf(StoredFileNotFoundException.class);
  }

  private static List<Path> walkJava(Path root) throws IOException {
    if (!Files.exists(root)) {
      return List.of();
    }
    try (Stream<Path> stream = Files.walk(root)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".java"))
          .toList();
    }
  }

  private static List<Path> concat(List<Path> a, List<Path> b) {
    List<Path> out = new ArrayList<>(a.size() + b.size());
    out.addAll(a);
    out.addAll(b);
    return out;
  }

  private static boolean declaresStorageImplementation(Path javaFile) {
    try {
      String body = Files.readString(javaFile, StandardCharsets.UTF_8);
      return body.contains("implements StoredDocumentStorage");
    } catch (IOException e) {
      throw new AssertionError("Failed to read " + javaFile, e);
    }
  }

  private static Path locateMainJavaRoot() {
    Path cwd = Paths.get("").toAbsolutePath();
    Path direct = cwd.resolve("src/main/java");
    if (Files.exists(direct)) {
      return direct;
    }
    Path nested = cwd.resolve("backend/src/main/java");
    if (Files.exists(nested)) {
      return nested;
    }
    throw new AssertionError("Could not locate src/main/java relative to cwd=" + cwd);
  }

  private static final class InMemoryStoredDocumentStorage implements StoredDocumentStorage {
    private final Map<StoredDocumentId, byte[]> store = new HashMap<>();

    @Override
    public void save(StoredDocumentId id, byte[] bytes) {
      store.put(id, bytes.clone());
    }

    @Override
    public byte[] load(StoredDocumentId id) {
      byte[] bytes = store.get(id);
      if (bytes == null) {
        throw new StoredFileNotFoundException(id);
      }
      return bytes.clone();
    }

    @Override
    public void delete(StoredDocumentId id) {
      store.remove(id);
    }
  }
}
