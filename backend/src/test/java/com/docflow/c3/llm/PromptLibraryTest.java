package com.docflow.c3.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docflow.config.catalog.DocumentTypeCatalog;
import com.docflow.config.catalog.DocumentTypeSchemaView;
import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.config.catalog.OrganizationView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PromptLibraryTest {

  private static PromptLibrary newLibrary(
      OrganizationCatalog org, DocumentTypeCatalog doc, String promptsRoot) {
    PromptLibrary library = new PromptLibrary(org, doc);
    library.setPromptsRoot(promptsRoot);
    return library;
  }

  @Test
  void promptTemplateRendersSubstitutionsViaPlainStringReplace() {
    PromptTemplate template = new PromptTemplate("Choose: {{ALLOWED_DOC_TYPES}}.");
    String rendered = template.render(Map.of("ALLOWED_DOC_TYPES", "invoice, receipt"));
    assertThat(rendered).isEqualTo("Choose: invoice, receipt.");
  }

  @Test
  void promptTemplateLeavesUnreplacedPlaceholdersIntact() {
    PromptTemplate template = new PromptTemplate("A={{A}} B={{B}}");
    String rendered = template.render(Map.of("A", "1"));
    assertThat(rendered).isEqualTo("A=1 B={{B}}");
  }

  @Test
  void validateLoadsClassifyAndAllAllowedExtractPrompts() {
    Map<String, List<String>> orgToDocTypes =
        Map.of("riverside-bistro", List.of("invoice", "receipt"));
    PromptLibrary library =
        newLibrary(
            new FakeOrganizationCatalog(orgToDocTypes),
            new FakeDocumentTypeCatalog(orgToDocTypes),
            "classpath:/prompts-test/");

    assertThat(library.getClassify().raw()).contains("{{ALLOWED_DOC_TYPES}}");
    assertThat(library.getExtract("invoice").raw()).contains("invoice");
    assertThat(library.getExtract("receipt").raw()).contains("receipt");
  }

  @Test
  void getExtractThrowsForDocTypeNotValidated() {
    Map<String, List<String>> orgToDocTypes =
        Map.of("riverside-bistro", List.of("invoice", "receipt"));
    PromptLibrary library =
        newLibrary(
            new FakeOrganizationCatalog(orgToDocTypes),
            new FakeDocumentTypeCatalog(orgToDocTypes),
            "classpath:/prompts-test/");

    assertThatThrownBy(() -> library.getExtract("expense-report"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expense-report");
  }

  @Test
  void getClassifyMemoizesAfterFirstAccess() {
    Map<String, List<String>> orgToDocTypes = Map.of("riverside-bistro", List.of("invoice"));
    PromptLibrary library =
        newLibrary(
            new FakeOrganizationCatalog(orgToDocTypes),
            new FakeDocumentTypeCatalog(orgToDocTypes),
            "classpath:/prompts-test/");

    PromptTemplate first = library.getClassify();
    PromptTemplate second = library.getClassify();
    assertThat(first).isSameAs(second);
  }

  @Test
  void validateFailsWhenExtractPromptFileMissing() {
    Map<String, List<String>> orgToDocTypes =
        Map.of("riverside-bistro", List.of("invoice", "lien-waiver"));
    PromptLibrary library =
        newLibrary(
            new FakeOrganizationCatalog(orgToDocTypes),
            new FakeDocumentTypeCatalog(orgToDocTypes),
            "classpath:/prompts-test/");

    assertThatThrownBy(library::getClassify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("extract_lien-waiver.txt");
  }

  @Test
  void validateFailsWhenClassifyPromptFileMissing() {
    Map<String, List<String>> orgToDocTypes = Map.of("riverside-bistro", List.of("invoice"));
    PromptLibrary library =
        newLibrary(
            new FakeOrganizationCatalog(orgToDocTypes),
            new FakeDocumentTypeCatalog(orgToDocTypes),
            "classpath:/prompts-test-empty/");

    assertThatThrownBy(library::getClassify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("classify.txt");
  }

  @Test
  void validateFailsWhenAllowedDocTypeMissingFromDocumentTypeCatalog() {
    Map<String, List<String>> orgToDocTypes = Map.of("riverside-bistro", List.of("invoice"));
    PromptLibrary library =
        newLibrary(
            new FakeOrganizationCatalog(orgToDocTypes),
            new FakeDocumentTypeCatalog(Map.of()),
            "classpath:/prompts-test/");

    assertThatThrownBy(library::getClassify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing from DocumentTypeCatalog");
  }

  private static final class FakeOrganizationCatalog implements OrganizationCatalog {
    private final Map<String, List<String>> orgToDocTypes;
    private final List<OrganizationView> ordered;

    FakeOrganizationCatalog(Map<String, List<String>> orgToDocTypes) {
      this.orgToDocTypes = new LinkedHashMap<>(orgToDocTypes);
      List<OrganizationView> views = new ArrayList<>();
      for (Map.Entry<String, List<String>> entry : this.orgToDocTypes.entrySet()) {
        views.add(
            new OrganizationView(
                entry.getKey(), entry.getKey(), "icon-" + entry.getKey(), entry.getValue()));
      }
      this.ordered = List.copyOf(views);
    }

    @Override
    public Optional<OrganizationView> getOrganization(String orgId) {
      return ordered.stream().filter(v -> v.id().equals(orgId)).findFirst();
    }

    @Override
    public List<OrganizationView> listOrganizations() {
      return ordered;
    }

    @Override
    public List<String> getAllowedDocTypes(String orgId) {
      return orgToDocTypes.getOrDefault(orgId, List.of());
    }
  }

  private static final class FakeDocumentTypeCatalog implements DocumentTypeCatalog {
    private final Map<String, List<String>> orgToDocTypes;

    FakeDocumentTypeCatalog(Map<String, List<String>> orgToDocTypes) {
      this.orgToDocTypes = orgToDocTypes;
    }

    @Override
    public Optional<DocumentTypeSchemaView> getDocumentTypeSchema(String orgId, String docTypeId) {
      List<String> allowed = orgToDocTypes.get(orgId);
      if (allowed == null || !allowed.contains(docTypeId)) {
        return Optional.empty();
      }
      return Optional.of(new DocumentTypeSchemaView(orgId, docTypeId, docTypeId, List.of()));
    }

    @Override
    public List<DocumentTypeSchemaView> listDocumentTypes(String orgId) {
      List<String> allowed = orgToDocTypes.getOrDefault(orgId, List.of());
      List<DocumentTypeSchemaView> views = new ArrayList<>(allowed.size());
      for (String docTypeId : allowed) {
        views.add(new DocumentTypeSchemaView(orgId, docTypeId, docTypeId, List.of()));
      }
      return List.copyOf(views);
    }
  }
}
