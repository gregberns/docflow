package com.docflow.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StageGuardTest {

  @Test
  void fieldEqualsReturnsTrueWhenObjectsEqual() {
    StageGuard guard = new StageGuard.FieldEquals("waiverType", "unconditional");
    assertThat(guard.evaluate(Map.of("waiverType", "unconditional"))).isTrue();
  }

  @Test
  void fieldEqualsReturnsFalseWhenValueMismatches() {
    StageGuard guard = new StageGuard.FieldEquals("waiverType", "unconditional");
    assertThat(guard.evaluate(Map.of("waiverType", "conditional"))).isFalse();
  }

  @Test
  void fieldEqualsReturnsFalseOnMissingKey() {
    StageGuard guard = new StageGuard.FieldEquals("waiverType", "unconditional");
    assertThat(guard.evaluate(Map.of("otherField", "x"))).isFalse();
  }

  @Test
  void fieldEqualsTreatsExplicitNullValueAsAbsent() {
    StageGuard guard = new StageGuard.FieldEquals("waiverType", "unconditional");
    Map<String, Object> fields = new HashMap<>();
    fields.put("waiverType", null);
    assertThat(guard.evaluate(fields)).isFalse();
  }

  @Test
  void fieldEqualsMatchesNullExpectedAgainstAbsentKey() {
    StageGuard guard = new StageGuard.FieldEquals("waiverType", null);
    assertThat(guard.evaluate(Map.of("otherField", "x"))).isTrue();
  }

  @Test
  void fieldNotEqualsReturnsFalseWhenObjectsEqual() {
    StageGuard guard = new StageGuard.FieldNotEquals("waiverType", "unconditional");
    assertThat(guard.evaluate(Map.of("waiverType", "unconditional"))).isFalse();
  }

  @Test
  void fieldNotEqualsReturnsTrueWhenValueMismatches() {
    StageGuard guard = new StageGuard.FieldNotEquals("waiverType", "unconditional");
    assertThat(guard.evaluate(Map.of("waiverType", "conditional"))).isTrue();
  }

  @Test
  void fieldNotEqualsReturnsTrueOnMissingKey() {
    StageGuard guard = new StageGuard.FieldNotEquals("waiverType", "unconditional");
    assertThat(guard.evaluate(Map.of("otherField", "x"))).isTrue();
  }

  @Test
  void alwaysReturnsTrueOnEmptyMap() {
    StageGuard guard = new StageGuard.Always();
    assertThat(guard.evaluate(Map.of())).isTrue();
  }

  @Test
  void alwaysReturnsTrueOnPopulatedMap() {
    StageGuard guard = new StageGuard.Always();
    assertThat(guard.evaluate(Map.of("waiverType", "conditional"))).isTrue();
  }

  @Test
  void exhaustiveSwitchOverSealedTypeCompilesAndDispatches() {
    StageGuard equals = new StageGuard.FieldEquals("k", "v");
    StageGuard notEquals = new StageGuard.FieldNotEquals("k", "v");
    StageGuard always = new StageGuard.Always();

    assertThat(describe(equals)).isEqualTo("equals:k");
    assertThat(describe(notEquals)).isEqualTo("notEquals:k");
    assertThat(describe(always)).isEqualTo("always");
  }

  private static String describe(StageGuard guard) {
    return switch (guard) {
      case StageGuard.FieldEquals fe -> "equals:" + fe.path();
      case StageGuard.FieldNotEquals fne -> "notEquals:" + fne.path();
      case StageGuard.Always a -> {
        assertThat(a).isNotNull();
        yield "always";
      }
    };
  }
}
