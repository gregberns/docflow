package com.docflow.config.catalog;

import com.docflow.config.persistence.OrganizationDocTypeEntity;
import com.docflow.config.persistence.OrganizationDocTypeRepository;
import com.docflow.config.persistence.OrganizationEntity;
import com.docflow.config.persistence.OrganizationRepository;
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
public class OrganizationCatalogImpl implements OrganizationCatalog {

  private final OrganizationRepository organizationRepository;
  private final OrganizationDocTypeRepository organizationDocTypeRepository;

  private volatile Snapshot snapshot;

  public OrganizationCatalogImpl(
      OrganizationRepository organizationRepository,
      OrganizationDocTypeRepository organizationDocTypeRepository) {
    this.organizationRepository = organizationRepository;
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
    List<OrganizationEntity> entities = organizationRepository.findAllByOrderByOrdinalAsc();
    Map<String, OrganizationEntity> byId = new LinkedHashMap<>();
    for (OrganizationEntity entity : entities) {
      byId.put(entity.getId(), entity);
    }

    Map<String, List<String>> sortedDocTypes = new LinkedHashMap<>();
    for (String orgId : byId.keySet()) {
      List<OrganizationDocTypeEntity> ordered =
          organizationDocTypeRepository.findByOrganizationIdOrderByOrdinalAsc(orgId);
      List<String> ids = new ArrayList<>(ordered.size());
      for (OrganizationDocTypeEntity link : ordered) {
        ids.add(link.getDocumentTypeId());
      }
      sortedDocTypes.put(orgId, List.copyOf(ids));
    }

    List<OrganizationView> assembled = new ArrayList<>(byId.size());
    Map<String, OrganizationView> byIdView = new LinkedHashMap<>();
    for (OrganizationEntity entity : byId.values()) {
      OrganizationView view =
          new OrganizationView(
              entity.getId(),
              entity.getDisplayName(),
              entity.getIconId(),
              sortedDocTypes.getOrDefault(entity.getId(), List.of()));
      assembled.add(view);
      byIdView.put(view.id(), view);
    }

    return new Snapshot(List.copyOf(assembled), Map.copyOf(byIdView));
  }

  @Override
  public Optional<OrganizationView> getOrganization(String orgId) {
    return Optional.ofNullable(snapshot().byId.get(orgId));
  }

  @Override
  public List<OrganizationView> listOrganizations() {
    return snapshot().ordered;
  }

  @Override
  public List<String> getAllowedDocTypes(String orgId) {
    OrganizationView view = snapshot().byId.get(orgId);
    return view == null ? List.of() : view.documentTypeIds();
  }

  private record Snapshot(List<OrganizationView> ordered, Map<String, OrganizationView> byId) {}
}
