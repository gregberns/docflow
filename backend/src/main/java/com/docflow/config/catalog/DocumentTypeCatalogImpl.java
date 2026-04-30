package com.docflow.config.catalog;

import com.docflow.config.persistence.DocumentTypeEntity;
import com.docflow.config.persistence.DocumentTypeRepository;
import com.docflow.config.persistence.OrganizationDocTypeEntity;
import com.docflow.config.persistence.OrganizationDocTypeRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@DependsOn("orgConfigSeeder")
@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
public class DocumentTypeCatalogImpl implements DocumentTypeCatalog {

  private final DocumentTypeRepository documentTypeRepository;
  private final OrganizationDocTypeRepository organizationDocTypeRepository;

  private volatile Snapshot snapshot;

  public DocumentTypeCatalogImpl(
      DocumentTypeRepository documentTypeRepository,
      OrganizationDocTypeRepository organizationDocTypeRepository) {
    this.documentTypeRepository = documentTypeRepository;
    this.organizationDocTypeRepository = organizationDocTypeRepository;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Order(Ordered.LOWEST_PRECEDENCE - 100)
  public void loadOnReady() {
    this.snapshot = load();
  }

  private Snapshot snapshot() {
    Snapshot local = snapshot;
    if (local == null) {
      throw new IllegalStateException("catalog not loaded — ApplicationReadyEvent has not fired");
    }
    return local;
  }

  private Snapshot load() {
    Map<String, Map<String, DocumentTypeSchemaView>> nested = new LinkedHashMap<>();
    Map<String, List<DocumentTypeSchemaView>> ordered = new LinkedHashMap<>();

    Map<String, List<OrganizationDocTypeEntity>> orderByOrg = new LinkedHashMap<>();
    for (OrganizationDocTypeEntity link : organizationDocTypeRepository.findAll()) {
      orderByOrg.computeIfAbsent(link.getOrganizationId(), k -> new ArrayList<>()).add(link);
    }

    for (String orgId : orderByOrg.keySet()) {
      List<OrganizationDocTypeEntity> orderedLinks =
          organizationDocTypeRepository.findByOrganizationIdOrderByOrdinalAsc(orgId);

      List<DocumentTypeEntity> entities = documentTypeRepository.findByOrganizationId(orgId);
      Map<String, DocumentTypeEntity> entitiesById = new LinkedHashMap<>();
      for (DocumentTypeEntity dt : entities) {
        entitiesById.put(dt.getId(), dt);
      }

      Map<String, DocumentTypeSchemaView> orgMap = new LinkedHashMap<>();
      List<DocumentTypeSchemaView> orgList = new ArrayList<>(orderedLinks.size());
      for (OrganizationDocTypeEntity link : orderedLinks) {
        DocumentTypeEntity entity = entitiesById.get(link.getDocumentTypeId());
        if (entity == null) {
          continue;
        }
        DocumentTypeSchemaView view = toView(entity);
        orgMap.put(view.id(), view);
        orgList.add(view);
      }

      nested.put(orgId, Map.copyOf(orgMap));
      ordered.put(orgId, List.copyOf(orgList));
    }

    return new Snapshot(Map.copyOf(nested), Map.copyOf(ordered));
  }

  private static DocumentTypeSchemaView toView(DocumentTypeEntity entity) {
    Map<String, Object> schema = entity.getFieldSchema();
    List<FieldView> fields = parseFields(schema);
    return new DocumentTypeSchemaView(
        entity.getOrganizationId(), entity.getId(), entity.getDisplayName(), fields);
  }

  @SuppressWarnings("unchecked")
  private static List<FieldView> parseFields(Map<String, Object> schemaMap) {
    if (schemaMap == null) {
      return List.of();
    }
    Object rawFields = schemaMap.get("fields");
    if (!(rawFields instanceof List<?> rawList)) {
      return List.of();
    }
    List<FieldView> result = new ArrayList<>(rawList.size());
    for (Object item : rawList) {
      if (item instanceof Map<?, ?> map) {
        result.add(parseField((Map<String, Object>) map));
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static FieldView parseField(Map<String, Object> raw) {
    String name = stringOrNull(raw.get("name"));
    String type = stringOrNull(raw.get("type"));
    Object requiredObj = raw.get("required");
    boolean required = requiredObj instanceof Boolean b && b;

    List<String> enumValues = null;
    Object rawEnumValues = raw.get("enumValues");
    if (rawEnumValues instanceof List<?> enumList) {
      List<String> collected = new ArrayList<>(enumList.size());
      for (Object value : enumList) {
        if (value != null) {
          collected.add(value.toString());
        }
      }
      enumValues = List.copyOf(collected);
    }

    String format = stringOrNull(raw.get("format"));

    List<FieldView> itemFields = null;
    Object rawItemSchema = raw.get("itemSchema");
    if (rawItemSchema instanceof Map<?, ?> itemMap) {
      itemFields = parseFields((Map<String, Object>) itemMap);
    }

    return new FieldView(name, type, required, enumValues, format, itemFields);
  }

  private static String stringOrNull(Object value) {
    return value == null ? null : value.toString();
  }

  @Override
  public Optional<DocumentTypeSchemaView> getDocumentTypeSchema(String orgId, String docTypeId) {
    Map<String, DocumentTypeSchemaView> orgMap = snapshot().byOrgAndId.get(orgId);
    if (orgMap == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(orgMap.get(docTypeId));
  }

  @Override
  public List<DocumentTypeSchemaView> listDocumentTypes(String orgId) {
    return snapshot().orderedByOrg.getOrDefault(orgId, List.of());
  }

  private record Snapshot(
      Map<String, Map<String, DocumentTypeSchemaView>> byOrgAndId,
      Map<String, List<DocumentTypeSchemaView>> orderedByOrg) {}
}
