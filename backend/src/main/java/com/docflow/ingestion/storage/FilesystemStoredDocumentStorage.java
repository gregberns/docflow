package com.docflow.ingestion.storage;

import com.docflow.config.AppConfig;
import com.docflow.ingestion.StoredDocumentId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Component;

@Component
public final class FilesystemStoredDocumentStorage implements StoredDocumentStorage {

  private static final String FILE_SUFFIX = ".bin";
  private static final String TMP_PREFIX = ".tmp-";

  private final Path storageRoot;

  public FilesystemStoredDocumentStorage(AppConfig.Storage storage) {
    this.storageRoot = Path.of(storage.storageRoot());
  }

  @Override
  public void save(StoredDocumentId id, byte[] bytes) {
    Path finalPath = resolve(id);
    Path parent = finalPath.getParent();
    try {
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Path tmp = Files.createTempFile(parent, TMP_PREFIX, FILE_SUFFIX);
      try {
        Files.write(tmp, bytes);
        Files.move(
            tmp, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        Files.deleteIfExists(tmp);
        throw e;
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to save stored document " + id, e);
    }
  }

  @Override
  public byte[] load(StoredDocumentId id) {
    Path path = resolve(id);
    try {
      return Files.readAllBytes(path);
    } catch (NoSuchFileException e) {
      throw new StoredFileNotFoundException(id);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load stored document " + id, e);
    }
  }

  @Override
  public void delete(StoredDocumentId id) {
    Path path = resolve(id);
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to delete stored document " + id, e);
    }
  }

  private Path resolve(StoredDocumentId id) {
    return storageRoot.resolve(id.value() + FILE_SUFFIX);
  }
}
