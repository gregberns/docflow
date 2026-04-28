package com.docflow.config.org.loader;

import com.docflow.config.org.DocTypeDefinition;
import com.docflow.config.org.OrgConfig;
import com.docflow.config.org.OrganizationDefinition;
import com.docflow.config.org.WorkflowDefinition;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

@Component
public class ConfigLoader {

  private static final String CLASSPATH_PREFIX = "classpath:";

  private final ObjectMapper yamlMapper;
  private final Validator validator;

  public ConfigLoader() {
    this(YAMLMapper.builder().build(), defaultValidator());
  }

  ConfigLoader(ObjectMapper yamlMapper, Validator validator) {
    this.yamlMapper = yamlMapper;
    this.validator = validator;
  }

  public OrgConfig load(String resourceRoot) {
    String root = normalizeRoot(resourceRoot);

    String orgsPath = root + "organizations.yaml";
    List<OrganizationDefinition> organizations =
        readYaml(orgsPath, new TypeReference<List<OrganizationDefinition>>() {});

    List<DocTypeDefinition> docTypes = new ArrayList<>();
    List<WorkflowDefinition> workflows = new ArrayList<>();

    for (OrganizationDefinition org : organizations) {
      for (String docTypeId : org.documentTypeIds()) {
        String docTypePath = root + "doc-types/" + org.id() + "/" + docTypeId + ".yaml";
        DocTypeDefinition docType = readYaml(docTypePath, DocTypeDefinition.class);
        docTypes.add(docType);

        String workflowPath = root + "workflows/" + org.id() + "/" + docTypeId + ".yaml";
        WorkflowDefinition workflow = readYaml(workflowPath, WorkflowDefinition.class);
        workflows.add(workflow);
      }
    }

    OrgConfig config = new OrgConfig(organizations, docTypes, workflows);
    runValidation(config, root);
    return config;
  }

  private <T> T readYaml(String resourcePath, Class<T> type) {
    try (InputStream in = openResource(resourcePath)) {
      return yamlMapper.readValue(in, type);
    } catch (JacksonException e) {
      throw wrapJackson(resourcePath, e);
    } catch (IOException e) {
      throw new ConfigLoadException(resourcePath, "failed to read YAML resource", e);
    }
  }

  private <T> T readYaml(String resourcePath, TypeReference<T> type) {
    try (InputStream in = openResource(resourcePath)) {
      return yamlMapper.readValue(in, type);
    } catch (JacksonException e) {
      throw wrapJackson(resourcePath, e);
    } catch (IOException e) {
      throw new ConfigLoadException(resourcePath, "failed to read YAML resource", e);
    }
  }

  private InputStream openResource(String resourcePath) {
    InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
    if (in == null) {
      in = ConfigLoader.class.getClassLoader().getResourceAsStream(resourcePath);
    }
    if (in == null) {
      throw new ConfigLoadException(resourcePath, "resource not found on classpath", null);
    }
    return in;
  }

  private void runValidation(OrgConfig config, String root) {
    Set<ConstraintViolation<OrgConfig>> violations = validator.validate(config);
    if (violations.isEmpty()) {
      return;
    }
    String summary =
        violations.stream()
            .map(v -> v.getPropertyPath() + " " + v.getMessage())
            .sorted()
            .collect(Collectors.joining("; "));
    ConstraintViolationException cause = new ConstraintViolationException(violations);
    throw new ConfigLoadException(
        root, "configuration failed Jakarta validation: " + summary, cause);
  }

  private static ConfigLoadException wrapJackson(String resourcePath, JacksonException e) {
    TokenStreamLocation loc = e.getLocation();
    int line = loc == null ? -1 : loc.getLineNr();
    int col = loc == null ? -1 : loc.getColumnNr();
    return new ConfigLoadException(resourcePath, line, col, e.getOriginalMessage(), e);
  }

  private static String normalizeRoot(String resourceRoot) {
    if (resourceRoot == null || resourceRoot.isBlank()) {
      throw new ConfigLoadException(resourceRoot, "resource root must not be blank", null);
    }
    String trimmed = resourceRoot;
    if (trimmed.startsWith(CLASSPATH_PREFIX)) {
      trimmed = trimmed.substring(CLASSPATH_PREFIX.length());
    }
    if (!trimmed.endsWith("/")) {
      trimmed = trimmed + "/";
    }
    return trimmed;
  }

  private static Validator defaultValidator() {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      return factory.getValidator();
    }
  }
}
