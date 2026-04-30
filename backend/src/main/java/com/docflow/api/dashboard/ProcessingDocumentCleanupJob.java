package com.docflow.api.dashboard;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ProcessingDocumentCleanupJob {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessingDocumentCleanupJob.class);

  private static final Duration RETENTION = Duration.ofDays(7);

  private static final String DELETE_FAILED_SQL =
      "DELETE FROM processing_documents "
          + "WHERE current_step = 'FAILED' AND updated_at < :cutoff";

  private final NamedParameterJdbcOperations jdbc;
  private final Clock clock;

  ProcessingDocumentCleanupJob(NamedParameterJdbcOperations jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Scheduled(cron = "0 15 3 * * *")
  public int sweep() {
    Instant cutoff = clock.instant().minus(RETENTION);
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("cutoff", Timestamp.from(cutoff));
    int deleted = jdbc.update(DELETE_FAILED_SQL, params);
    if (deleted > 0) {
      LOG.info("Deleted {} failed processing_documents row(s) older than {}", deleted, cutoff);
    }
    return deleted;
  }
}
