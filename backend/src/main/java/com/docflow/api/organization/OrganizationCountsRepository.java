package com.docflow.api.organization;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
public class OrganizationCountsRepository {

  private static final String IN_PROGRESS_SQL =
      "SELECT pd.organization_id AS org_id, COUNT(*) AS cnt "
          + "FROM processing_documents pd "
          + "LEFT JOIN documents d ON d.stored_document_id = pd.stored_document_id "
          + "WHERE d.id IS NULL "
          + "GROUP BY pd.organization_id";

  private static final String FILED_SQL =
      "SELECT wi.organization_id AS org_id, COUNT(*) AS cnt "
          + "FROM workflow_instances wi "
          + "WHERE wi.current_status = 'FILED' "
          + "GROUP BY wi.organization_id";

  private final NamedParameterJdbcTemplate jdbc;

  public OrganizationCountsRepository(DataSource dataSource) {
    this.jdbc = new NamedParameterJdbcTemplate(dataSource);
  }

  public Map<String, Counts> countsByOrg() {
    Map<String, Long> inProgress = queryToMap(IN_PROGRESS_SQL);
    Map<String, Long> filed = queryToMap(FILED_SQL);

    Map<String, Counts> merged = new LinkedHashMap<>();
    for (Map.Entry<String, Long> e : inProgress.entrySet()) {
      merged.put(e.getKey(), new Counts(e.getValue(), filed.getOrDefault(e.getKey(), 0L)));
    }
    for (Map.Entry<String, Long> e : filed.entrySet()) {
      merged.computeIfAbsent(e.getKey(), k -> new Counts(0L, e.getValue()));
    }
    return merged;
  }

  public Counts countsForOrg(String orgId) {
    return countsByOrg().getOrDefault(orgId, new Counts(0L, 0L));
  }

  private Map<String, Long> queryToMap(String sql) {
    List<Map<String, Object>> rows = jdbc.queryForList(sql, Map.of());
    Map<String, Long> result = new HashMap<>(rows.size());
    for (Map<String, Object> row : rows) {
      String orgId = (String) row.get("org_id");
      Number count = (Number) row.get("cnt");
      result.put(orgId, count == null ? 0L : count.longValue());
    }
    return result;
  }

  public record Counts(long inProgressCount, long filedCount) {}
}
