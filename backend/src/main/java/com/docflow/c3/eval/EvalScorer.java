package com.docflow.c3.eval;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregates classification and extraction accuracy across eval samples per the spec's
 * single-metric-per-axis policy:
 *
 * <ul>
 *   <li>Classification: per-sample boolean (predicted == expected).
 *   <li>Extraction: walks every scalar in the expected payload (including nested rows),
 *       normalize-and-exact-match against the predicted scalar at the same path; aggregates as
 *       {@code matchedScalars / totalScalars}.
 * </ul>
 *
 * Pure — no I/O, no LLM calls.
 */
public final class EvalScorer {

  public SampleScore score(
      String samplePath,
      String expectedDocType,
      String predictedDocType,
      Map<String, Object> expectedFields,
      Map<String, Object> predictedFields) {
    Objects.requireNonNull(samplePath, "samplePath");
    Objects.requireNonNull(expectedDocType, "expectedDocType");
    Objects.requireNonNull(expectedFields, "expectedFields");

    boolean classifyHit = expectedDocType.equals(predictedDocType);
    Map<String, Object> predictedSafe = predictedFields == null ? Map.of() : predictedFields;
    FieldTally tally = new FieldTally();
    walkObject(expectedFields, predictedSafe, tally);
    return new SampleScore(
        samplePath, expectedDocType, predictedDocType, classifyHit, tally.matched, tally.total);
  }

  public AggregateScore aggregate(List<SampleScore> samples) {
    Objects.requireNonNull(samples, "samples");
    int classifyTotal = samples.size();
    int classifyHit = 0;
    int fieldTotal = 0;
    int fieldMatched = 0;
    for (SampleScore s : samples) {
      if (s.classifyHit()) {
        classifyHit++;
      }
      fieldTotal += s.fieldsTotal();
      fieldMatched += s.fieldsMatched();
    }
    return new AggregateScore(classifyHit, classifyTotal, fieldMatched, fieldTotal);
  }

  private void walkObject(
      Map<String, Object> expected, Map<String, Object> predicted, FieldTally tally) {
    for (Map.Entry<String, Object> entry : expected.entrySet()) {
      Object expectedValue = entry.getValue();
      Object predictedValue = predicted == null ? null : predicted.get(entry.getKey());
      walkValue(expectedValue, predictedValue, tally);
    }
  }

  private void walkValue(Object expected, Object predicted, FieldTally tally) {
    if (expected instanceof Map<?, ?> expectedMap) {
      Map<String, Object> expectedTyped = castMap(expectedMap);
      Map<String, Object> predictedTyped =
          predicted instanceof Map<?, ?> pm ? castMap(pm) : Map.of();
      walkObject(expectedTyped, predictedTyped, tally);
      return;
    }
    if (expected instanceof List<?> expectedList) {
      List<?> predictedList = predicted instanceof List<?> pl ? pl : List.of();
      int n = expectedList.size();
      for (int i = 0; i < n; i++) {
        Object e = expectedList.get(i);
        Object p = i < predictedList.size() ? predictedList.get(i) : null;
        walkValue(e, p, tally);
      }
      return;
    }
    tally.total++;
    if (matches(expected, predicted)) {
      tally.matched++;
    }
  }

  static boolean matches(Object expected, Object predicted) {
    String e = normalize(expected);
    String p = normalize(predicted);
    return e.equals(p);
  }

  static String normalize(Object value) {
    if (value == null) {
      return "";
    }
    String s = String.valueOf(value).trim();
    if (s.isEmpty()) {
      return "";
    }
    return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }

  @SuppressWarnings("unchecked") // why: yaml/json maps deserialized as raw Map<?,?>; safe to widen.
  private static Map<String, Object> castMap(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }

  private static final class FieldTally {
    int matched;
    int total;
  }

  public record SampleScore(
      String samplePath,
      String expectedDocType,
      String predictedDocType,
      boolean classifyHit,
      int fieldsMatched,
      int fieldsTotal) {}

  public record AggregateScore(
      int classifyHit, int classifyTotal, int fieldsMatched, int fieldsTotal) {

    public double classifyAccuracy() {
      return classifyTotal == 0 ? 0.0 : (double) classifyHit / classifyTotal;
    }

    public double fieldAccuracy() {
      return fieldsTotal == 0 ? 0.0 : (double) fieldsMatched / fieldsTotal;
    }
  }
}
