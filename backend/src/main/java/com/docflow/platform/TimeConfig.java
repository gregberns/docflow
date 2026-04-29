package com.docflow.platform;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

  @Bean
  @ConditionalOnMissingBean
  public Clock systemClock() {
    return Clock.systemUTC();
  }
}
