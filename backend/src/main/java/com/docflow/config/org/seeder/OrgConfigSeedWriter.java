package com.docflow.config.org.seeder;

import com.docflow.config.org.DocTypeDefinition;
import com.docflow.config.org.FieldDefinition;
import com.docflow.config.org.OrgConfig;
import com.docflow.config.org.OrganizationDefinition;
import com.docflow.config.org.StageDefinition;
import com.docflow.config.org.TransitionDefinition;
import com.docflow.config.org.WorkflowDefinition;
import com.docflow.config.persistence.DocumentTypeEntity;
import com.docflow.config.persistence.DocumentTypeRepository;
import com.docflow.config.persistence.OrganizationDocTypeEntity;
import com.docflow.config.persistence.OrganizationDocTypeRepository;
import com.docflow.config.persistence.OrganizationEntity;
import com.docflow.config.persistence.OrganizationRepository;
import com.docflow.config.persistence.StageEntity;
import com.docflow.config.persistence.StageRepository;
import com.docflow.config.persistence.TransitionEntity;
import com.docflow.config.persistence.TransitionRepository;
import com.docflow.config.persistence.WorkflowEntity;
import com.docflow.config.persistence.WorkflowRepository;
import com.github.f4b6a3.uuid.UuidCreator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Constructing one entity per seeded row is the point of this class; the PMD
// performance rule is not applicable here.
@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
@Component
public class OrgConfigSeedWriter {

  private final OrganizationRepository organizationRepository;
  private final DocumentTypeRepository documentTypeRepository;
  private final OrganizationDocTypeRepository organizationDocTypeRepository;
  private final WorkflowRepository workflowRepository;
  private final StageRepository stageRepository;
  private final TransitionRepository transitionRepository;

  public OrgConfigSeedWriter(
      OrganizationRepository organizationRepository,
      DocumentTypeRepository documentTypeRepository,
      OrganizationDocTypeRepository organizationDocTypeRepository,
      WorkflowRepository workflowRepository,
      StageRepository stageRepository,
      TransitionRepository transitionRepository) {
    this.organizationRepository = organizationRepository;
    this.documentTypeRepository = documentTypeRepository;
    this.organizationDocTypeRepository = organizationDocTypeRepository;
    this.workflowRepository = workflowRepository;
    this.stageRepository = stageRepository;
    this.transitionRepository = transitionRepository;
  }

  @Transactional
  public void persist(OrgConfig config) {
    persistOrganizations(config.organizations());
    persistDocumentTypes(config.docTypes());
    persistOrganizationDocTypes(config.organizations());
    persistWorkflows(config.workflows());
  }

  private void persistOrganizations(List<OrganizationDefinition> organizations) {
    for (int i = 0; i < organizations.size(); i++) {
      OrganizationDefinition org = organizations.get(i);
      organizationRepository.save(
          new OrganizationEntity(org.id(), org.displayName(), org.iconId(), i));
    }
  }

  private void persistDocumentTypes(List<DocTypeDefinition> docTypes) {
    for (DocTypeDefinition dt : docTypes) {
      documentTypeRepository.save(
          new DocumentTypeEntity(
              dt.organizationId(),
              dt.id(),
              dt.displayName(),
              dt.inputModality().name(),
              fieldSchemaMap(dt.fields())));
    }
  }

  private void persistOrganizationDocTypes(List<OrganizationDefinition> organizations) {
    for (OrganizationDefinition org : organizations) {
      List<String> ids = org.documentTypeIds();
      for (int i = 0; i < ids.size(); i++) {
        organizationDocTypeRepository.save(new OrganizationDocTypeEntity(org.id(), ids.get(i), i));
      }
    }
  }

  private void persistWorkflows(List<WorkflowDefinition> workflows) {
    for (WorkflowDefinition workflow : workflows) {
      workflowRepository.save(
          new WorkflowEntity(workflow.organizationId(), workflow.documentTypeId()));
      persistStages(workflow);
      persistTransitions(workflow);
    }
  }

  private void persistStages(WorkflowDefinition workflow) {
    List<StageDefinition> stages = workflow.stages();
    for (int i = 0; i < stages.size(); i++) {
      StageDefinition stage = stages.get(i);
      stageRepository.save(
          new StageEntity(
              workflow.organizationId(),
              workflow.documentTypeId(),
              stage.id(),
              stage.displayName(),
              stage.kind().name(),
              stage.canonicalStatus().name(),
              stage.role(),
              i));
    }
  }

  private void persistTransitions(WorkflowDefinition workflow) {
    List<TransitionDefinition> transitions = workflow.transitions();
    for (int i = 0; i < transitions.size(); i++) {
      TransitionDefinition transition = transitions.get(i);
      TransitionEntity.TransitionGuard guard =
          transition.guard() == null
              ? null
              : new TransitionEntity.TransitionGuard(
                  transition.guard().field(),
                  transition.guard().op().name(),
                  transition.guard().value());
      transitionRepository.save(
          new TransitionEntity(
              UuidCreator.getTimeOrderedEpoch(),
              new TransitionEntity.TransitionKey(
                  workflow.organizationId(),
                  workflow.documentTypeId(),
                  transition.from(),
                  transition.to(),
                  transition.action().name()),
              guard,
              i));
    }
  }

  private static Map<String, Object> fieldSchemaMap(List<FieldDefinition> fields) {
    List<Map<String, Object>> serialized = new ArrayList<>(fields.size());
    for (FieldDefinition field : fields) {
      serialized.add(fieldMap(field));
    }
    return Map.of("fields", serialized);
  }

  private static Map<String, Object> fieldMap(FieldDefinition field) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", field.name());
    map.put("type", field.type().name());
    map.put("required", field.required());
    if (field.enumValues() != null && !field.enumValues().isEmpty()) {
      map.put("enumValues", List.copyOf(field.enumValues()));
    }
    if (field.itemSchema() != null) {
      map.put("itemSchema", fieldSchemaMap(field.itemSchema().fields()));
    }
    return map;
  }
}
