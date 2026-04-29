package com.docflow.config.org.seeder;

import com.docflow.config.AppConfig;
import com.docflow.config.org.OrgConfig;
import com.docflow.config.org.loader.ConfigLoader;
import com.docflow.config.org.validation.ConfigValidator;
import com.docflow.config.persistence.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OrgConfigSeeder {

  private static final Logger LOG = LoggerFactory.getLogger(OrgConfigSeeder.class);

  private final AppConfig appConfig;
  private final ConfigLoader configLoader;
  private final ConfigValidator configValidator;
  private final OrgConfigSeedWriter seedWriter;
  private final OrganizationRepository organizationRepository;

  public OrgConfigSeeder(
      AppConfig appConfig,
      ConfigLoader configLoader,
      ConfigValidator configValidator,
      OrgConfigSeedWriter seedWriter,
      OrganizationRepository organizationRepository) {
    this.appConfig = appConfig;
    this.configLoader = configLoader;
    this.configValidator = configValidator;
    this.seedWriter = seedWriter;
    this.organizationRepository = organizationRepository;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void seedOnReady() {
    if (!appConfig.config().seedOnBoot()) {
      LOG.info("OrgConfigSeeder: seedOnBoot=false, skipping");
      return;
    }
    long existing = organizationRepository.count();
    if (existing > 0) {
      LOG.info("OrgConfigSeeder: seed skipped (organizations table non-empty, count={})", existing);
      return;
    }
    OrgConfig parsed = configLoader.load(appConfig.config().seedResourcePath());
    configValidator.validate(parsed);
    seedWriter.persist(parsed);
    LOG.info(
        "OrgConfigSeeder: seeded {} organizations, {} document types, {} workflows",
        parsed.organizations().size(),
        parsed.docTypes().size(),
        parsed.workflows().size());
  }
}
