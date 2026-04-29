package com.docflow.c3.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.docflow.c3.eval.EvalScorer.AggregateScore;
import com.docflow.c3.eval.EvalScorer.SampleScore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvalScorerTest {

  private final EvalScorer scorer = new EvalScorer();

  @Test
  void scalarFieldExactMatchCountsAsHit() {
    SampleScore score =
        scorer.score(
            "x.pdf", "invoice", "invoice", Map.of("vendor", "Acme"), Map.of("vendor", "Acme"));
    assertThat(score.classifyHit()).isTrue();
    assertThat(score.fieldsTotal()).isEqualTo(1);
    assertThat(score.fieldsMatched()).isEqualTo(1);
  }

  @Test
  void normalizationIsCaseAndWhitespaceInsensitive() {
    SampleScore score =
        scorer.score(
            "x.pdf",
            "invoice",
            "invoice",
            Map.of("vendor", "Acme  Corp"),
            Map.of("vendor", "  acme corp"));
    assertThat(score.fieldsMatched()).isEqualTo(1);
  }

  @Test
  void missingPredictedFieldCountsAsMiss() {
    SampleScore score =
        scorer.score("x.pdf", "invoice", "invoice", Map.of("vendor", "Acme"), Map.of());
    assertThat(score.fieldsTotal()).isEqualTo(1);
    assertThat(score.fieldsMatched()).isZero();
  }

  @Test
  void wrongClassificationStillScoresFields() {
    SampleScore score =
        scorer.score(
            "x.pdf", "invoice", "receipt", Map.of("vendor", "Acme"), Map.of("vendor", "Acme"));
    assertThat(score.classifyHit()).isFalse();
    assertThat(score.fieldsMatched()).isEqualTo(1);
    assertThat(score.fieldsTotal()).isEqualTo(1);
  }

  @Test
  void nullPredictedDocTypeMissesClassification() {
    SampleScore score = scorer.score("x.pdf", "invoice", null, Map.of("vendor", "Acme"), Map.of());
    assertThat(score.classifyHit()).isFalse();
    assertThat(score.predictedDocType()).isNull();
  }

  @Test
  void nestedArrayWalksRowByRow() {
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put(
        "lineItems",
        List.of(
            Map.of("description", "Foo", "quantity", "1"),
            Map.of("description", "Bar", "quantity", "2")));
    Map<String, Object> predicted = new LinkedHashMap<>();
    predicted.put(
        "lineItems",
        List.of(
            Map.of("description", "Foo", "quantity", "1"),
            Map.of("description", "Bar", "quantity", "9")));
    SampleScore score = scorer.score("x.pdf", "invoice", "invoice", expected, predicted);
    assertThat(score.fieldsTotal()).isEqualTo(4);
    assertThat(score.fieldsMatched()).isEqualTo(3);
  }

  @Test
  void shorterPredictedArrayMissesTailingScalars() {
    Map<String, Object> expected =
        Map.of("items", List.of(Map.of("description", "Foo"), Map.of("description", "Bar")));
    Map<String, Object> predicted = Map.of("items", List.of(Map.of("description", "Foo")));
    SampleScore score =
        scorer.score("x.pdf", "expense-report", "expense-report", expected, predicted);
    assertThat(score.fieldsTotal()).isEqualTo(2);
    assertThat(score.fieldsMatched()).isEqualTo(1);
  }

  @Test
  void aggregateAcrossThreeSamples() {
    SampleScore s1 =
        scorer.score(
            "a.pdf", "invoice", "invoice", Map.of("vendor", "Acme"), Map.of("vendor", "Acme"));
    SampleScore s2 =
        scorer.score(
            "b.pdf",
            "receipt",
            "invoice",
            Map.of("merchant", "Bob's"),
            Map.of("merchant", "Different"));
    SampleScore s3 =
        scorer.score(
            "c.pdf",
            "expense-report",
            "expense-report",
            Map.of("totalAmount", "100", "submitter", "Eve"),
            Map.of("totalAmount", "100", "submitter", "Eve"));
    AggregateScore agg = scorer.aggregate(List.of(s1, s2, s3));
    assertThat(agg.classifyHit()).isEqualTo(2);
    assertThat(agg.classifyTotal()).isEqualTo(3);
    assertThat(agg.fieldsMatched()).isEqualTo(3);
    assertThat(agg.fieldsTotal()).isEqualTo(4);
    assertThat(agg.classifyAccuracy()).isEqualTo(2.0 / 3.0);
    assertThat(agg.fieldAccuracy()).isEqualTo(0.75);
  }

  @Test
  void aggregateOfEmptyListIsZeros() {
    AggregateScore agg = scorer.aggregate(List.of());
    assertThat(agg.classifyTotal()).isZero();
    assertThat(agg.classifyAccuracy()).isZero();
    assertThat(agg.fieldAccuracy()).isZero();
  }

  @Test
  void normalizesNumericVsStringSameValue() {
    SampleScore score =
        scorer.score(
            "x.pdf", "invoice", "invoice", Map.of("amount", "1234.56"), Map.of("amount", 1234.56));
    assertThat(score.fieldsMatched()).isEqualTo(1);
  }
}
