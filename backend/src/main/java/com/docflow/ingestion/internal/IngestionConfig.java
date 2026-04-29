package com.docflow.ingestion.internal;

import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IngestionConfig {

  @Bean
  Detector tikaDetector() {
    return TikaConfig.getDefaultConfig().getDetector();
  }
}
