package com.docflow.ingestion.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docflow.config.AppConfig;
import com.docflow.ingestion.StoredDocumentId;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemStoredDocumentStorageTest {

  @Test
  void roundTripReturnsIdenticalBytesAtExpectedPath(@TempDir Path root) {
    StoredDocumentStorage storage = newStorage(root);
    StoredDocumentId id = StoredDocumentId.generate();
    byte[] bytes = "hello docflow".getBytes(StandardCharsets.UTF_8);

    storage.save(id, bytes);

    Path expected = root.resolve(id.value() + ".bin");
    assertThat(expected).exists();
    assertThat(storage.load(id)).isEqualTo(bytes);
  }

  @Test
  void loadOfMissingFileThrowsStoredFileNotFoundException(@TempDir Path root) {
    StoredDocumentStorage storage = newStorage(root);
    StoredDocumentId id = StoredDocumentId.generate();

    assertThatThrownBy(() -> storage.load(id))
        .isInstanceOf(StoredFileNotFoundException.class)
        .extracting("id")
        .isEqualTo(id);
  }

  @Test
  void openStreamReturnsByteForByteIdenticalContentAndSizeMatchesPayload(@TempDir Path root)
      throws Exception {
    StoredDocumentStorage storage = newStorage(root);
    StoredDocumentId id = StoredDocumentId.generate();
    byte[] bytes = "stream-me".getBytes(StandardCharsets.UTF_8);
    storage.save(id, bytes);

    assertThat(storage.size(id)).isEqualTo(bytes.length);
    try (InputStream in = storage.openStream(id)) {
      assertThat(in.readAllBytes()).isEqualTo(bytes);
    }
  }

  @Test
  void openStreamAndSizeOfMissingFileThrowStoredFileNotFoundException(@TempDir Path root) {
    StoredDocumentStorage storage = newStorage(root);
    StoredDocumentId id = StoredDocumentId.generate();

    assertThatThrownBy(() -> storage.openStream(id))
        .isInstanceOf(StoredFileNotFoundException.class)
        .extracting("id")
        .isEqualTo(id);
    assertThatThrownBy(() -> storage.size(id))
        .isInstanceOf(StoredFileNotFoundException.class)
        .extracting("id")
        .isEqualTo(id);
  }

  @Test
  void saveCreatesParentDirectoriesIfMissing(@TempDir Path root) {
    Path nested = root.resolve("nested").resolve("documents");
    StoredDocumentStorage storage = newStorage(nested);
    StoredDocumentId id = StoredDocumentId.generate();
    byte[] bytes = {1, 2, 3, 4};

    storage.save(id, bytes);

    assertThat(nested.resolve(id.value() + ".bin")).exists();
    assertThat(storage.load(id)).isEqualTo(bytes);
  }

  @Test
  void saveOverwritesExistingFile(@TempDir Path root) {
    StoredDocumentStorage storage = newStorage(root);
    StoredDocumentId id = StoredDocumentId.generate();

    storage.save(id, "first".getBytes(StandardCharsets.UTF_8));
    storage.save(id, "second".getBytes(StandardCharsets.UTF_8));

    assertThat(storage.load(id)).isEqualTo("second".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void saveLeavesNoTempFileBehind(@TempDir Path root) throws Exception {
    StoredDocumentStorage storage = newStorage(root);
    StoredDocumentId id = StoredDocumentId.generate();

    storage.save(id, new byte[] {7, 7, 7});

    try (Stream<Path> entries = Files.list(root)) {
      List<Path> remaining = entries.toList();
      assertThat(remaining).hasSize(1);
      assertThat(remaining.get(0).getFileName().toString()).isEqualTo(id.value() + ".bin");
    }
  }

  @Test
  void deleteRemovesTheFileAndIsIdempotent(@TempDir Path root) {
    StoredDocumentStorage storage = newStorage(root);
    StoredDocumentId id = StoredDocumentId.generate();
    storage.save(id, new byte[] {0});

    storage.delete(id);
    assertThat(root.resolve(id.value() + ".bin")).doesNotExist();

    storage.delete(id);
  }

  @Test
  void inMemoryImplementationSatisfiesTheSameSeamWithoutProductionChange(@TempDir Path root) {
    List<StoredDocumentStorage> impls =
        List.of(newStorage(root), new InMemoryStoredDocumentStorage());

    for (StoredDocumentStorage impl : impls) {
      StoredDocumentId id = StoredDocumentId.generate();
      byte[] payload = "seam-check".getBytes(StandardCharsets.UTF_8);

      impl.save(id, payload);
      assertThat(impl.load(id)).isEqualTo(payload);

      StoredDocumentId missing = StoredDocumentId.generate();
      assertThatThrownBy(() -> impl.load(missing)).isInstanceOf(StoredFileNotFoundException.class);

      impl.delete(id);
      assertThatThrownBy(() -> impl.load(id)).isInstanceOf(StoredFileNotFoundException.class);
    }
  }

  private static StoredDocumentStorage newStorage(Path root) {
    return new FilesystemStoredDocumentStorage(new AppConfig.Storage(root.toString()));
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
    public InputStream openStream(StoredDocumentId id) {
      byte[] bytes = store.get(id);
      if (bytes == null) {
        throw new StoredFileNotFoundException(id);
      }
      return new ByteArrayInputStream(bytes.clone());
    }

    @Override
    public long size(StoredDocumentId id) {
      byte[] bytes = store.get(id);
      if (bytes == null) {
        throw new StoredFileNotFoundException(id);
      }
      return bytes.length;
    }

    @Override
    public void delete(StoredDocumentId id) {
      store.remove(id);
    }
  }
}
