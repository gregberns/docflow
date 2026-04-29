package com.docflow.c3.llm;

import com.docflow.config.catalog.DocumentTypeCatalog;
import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.config.catalog.OrganizationView;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
public class PromptLibrary {

  static final String DEFAULT_PROMPTS_ROOT = "classpath:/prompts/";

  private static final String CLASSPATH_PREFIX = "classpath:";
  private static final String CLASSIFY_FILE = "classify.txt";
  private static final String EXTRACT_PREFIX = "extract_";
  private static final String EXTRACT_SUFFIX = ".txt";

  private final OrganizationCatalog organizationCatalog;
  private final DocumentTypeCatalog documentTypeCatalog;
  private String promptsRoot;

  private volatile Snapshot snapshot;

  public PromptLibrary(
      OrganizationCatalog organizationCatalog, DocumentTypeCatalog documentTypeCatalog) {
    this.organizationCatalog = organizationCatalog;
    this.documentTypeCatalog = documentTypeCatalog;
    this.promptsRoot = DEFAULT_PROMPTS_ROOT;
  }

  void setPromptsRoot(String promptsRoot) {
    this.promptsRoot = promptsRoot;
  }

  // Must run after OrganizationCatalog + DocumentTypeCatalog populate their snapshots
  // (both at LOWEST_PRECEDENCE - 100). Equal @Order would tie-break by bean name, which is fragile.
  @EventListener(ApplicationReadyEvent.class)
  @Order(Ordered.LOWEST_PRECEDENCE)
  void validateOnReady() {
    snapshot();
  }

  private synchronized Snapshot snapshot() {
    Snapshot local = snapshot;
    if (local != null) {
      return local;
    }
    Snapshot built = validate();
    this.snapshot = built;
    return built;
  }

  Snapshot validate() {
    String root = normalizeRoot(promptsRoot);

    PromptTemplate loadedClassify = loadTemplate(root + CLASSIFY_FILE);

    Set<String> required = collectRequiredDocTypes();
    Map<String, PromptTemplate> loaded = new LinkedHashMap<>();
    for (String docTypeId : required) {
      String path = root + EXTRACT_PREFIX + docTypeId + EXTRACT_SUFFIX;
      loaded.put(docTypeId, loadTemplate(path));
    }

    return new Snapshot(loadedClassify, Map.copyOf(loaded));
  }

  private Set<String> collectRequiredDocTypes() {
    Set<String> result = new TreeSet<>();
    List<OrganizationView> orgs = organizationCatalog.listOrganizations();
    for (OrganizationView org : orgs) {
      List<String> allowed = organizationCatalog.getAllowedDocTypes(org.id());
      for (String docTypeId : allowed) {
        if (documentTypeCatalog.getDocumentTypeSchema(org.id(), docTypeId).isEmpty()) {
          throw new IllegalStateException(
              "organization '"
                  + org.id()
                  + "' references document type '"
                  + docTypeId
                  + "' that is missing from DocumentTypeCatalog");
        }
        result.add(docTypeId);
      }
    }
    return result;
  }

  private PromptTemplate loadTemplate(String resourcePath) {
    try (InputStream in = openResource(resourcePath)) {
      byte[] bytes = in.readAllBytes();
      return new PromptTemplate(new String(bytes, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("failed to read prompt resource: " + resourcePath, e);
    }
  }

  private InputStream openResource(String resourcePath) {
    InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
    if (in == null) {
      in = PromptLibrary.class.getClassLoader().getResourceAsStream(resourcePath);
    }
    if (in == null) {
      throw new IllegalStateException("prompt resource not found on classpath: " + resourcePath);
    }
    return in;
  }

  public PromptTemplate getClassify() {
    return snapshot().classify();
  }

  public PromptTemplate getExtract(String docTypeId) {
    PromptTemplate template = snapshot().extractByDocType().get(docTypeId);
    if (template == null) {
      throw new IllegalStateException(
          "no extract prompt registered for doc type '" + docTypeId + "'");
    }
    return template;
  }

  private static String normalizeRoot(String root) {
    if (root == null || root.isBlank()) {
      throw new IllegalStateException("PromptLibrary prompts root must not be blank");
    }
    String trimmed = root;
    if (trimmed.startsWith(CLASSPATH_PREFIX)) {
      trimmed = trimmed.substring(CLASSPATH_PREFIX.length());
    }
    while (trimmed.startsWith("/")) {
      trimmed = trimmed.substring(1);
    }
    if (!trimmed.endsWith("/")) {
      trimmed = trimmed + "/";
    }
    return trimmed;
  }

  record Snapshot(PromptTemplate classify, Map<String, PromptTemplate> extractByDocType) {}
}
