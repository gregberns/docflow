package com.docflow.config.org;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StageGuardConfigTest {

  @Test
  void eqMatchReturnsTrue() {
    StageGuardConfig guard = new StageGuardConfig("waiverType", GuardOp.EQ, "unconditional");
    assertThat(guard.evaluate(Map.of("waiverType", "unconditional"))).isTrue();
  }

  @Test
  void eqMismatchReturnsFalse() {
    StageGuardConfig guard = new StageGuardConfig("waiverType", GuardOp.EQ, "unconditional");
    assertThat(guard.evaluate(Map.of("waiverType", "conditional"))).isFalse();
  }

  @Test
  void eqOnMissingKeyReturnsFalse() {
    StageGuardConfig guard = new StageGuardConfig("waiverType", GuardOp.EQ, "unconditional");
    assertThat(guard.evaluate(Map.of("otherField", "x"))).isFalse();
  }

  @Test
  void neqMatchReturnsFalse() {
    StageGuardConfig guard = new StageGuardConfig("waiverType", GuardOp.NEQ, "unconditional");
    assertThat(guard.evaluate(Map.of("waiverType", "unconditional"))).isFalse();
  }

  @Test
  void neqMismatchReturnsTrue() {
    StageGuardConfig guard = new StageGuardConfig("waiverType", GuardOp.NEQ, "unconditional");
    assertThat(guard.evaluate(Map.of("waiverType", "conditional"))).isTrue();
  }

  @Test
  void neqOnMissingKeyReturnsTrue() {
    StageGuardConfig guard = new StageGuardConfig("waiverType", GuardOp.NEQ, "unconditional");
    assertThat(guard.evaluate(Map.of("otherField", "x"))).isTrue();
  }

  @Test
  void eqWithNullValueInMapTreatedAsAbsent() {
    StageGuardConfig guard = new StageGuardConfig("waiverType", GuardOp.EQ, "unconditional");
    Map<String, Object> fields = new HashMap<>();
    fields.put("waiverType", null);
    assertThat(guard.evaluate(fields)).isFalse();
  }

  @Test
  void neqWithNullValueInMapTreatedAsAbsent() {
    StageGuardConfig guard = new StageGuardConfig("waiverType", GuardOp.NEQ, "unconditional");
    Map<String, Object> fields = new HashMap<>();
    fields.put("waiverType", null);
    assertThat(guard.evaluate(fields)).isTrue();
  }

  @Test
  void eqStringifiesNonStringValues() {
    StageGuardConfig guard = new StageGuardConfig("amount", GuardOp.EQ, "42");
    assertThat(guard.evaluate(Map.of("amount", 42))).isTrue();
  }

  @Test
  void neqStringifiesNonStringValues() {
    StageGuardConfig guard = new StageGuardConfig("amount", GuardOp.NEQ, "42");
    assertThat(guard.evaluate(Map.of("amount", 43))).isTrue();
  }

  @Test
  void evaluateHandlesNullFieldsMap() {
    StageGuardConfig eq = new StageGuardConfig("waiverType", GuardOp.EQ, "unconditional");
    StageGuardConfig neq = new StageGuardConfig("waiverType", GuardOp.NEQ, "unconditional");
    assertThat(eq.evaluate(null)).isFalse();
    assertThat(neq.evaluate(null)).isTrue();
  }

  @Test
  void workflowStatusEnumExposesExactlyFiveValuesInSpecOrder() {
    assertThat(WorkflowStatus.values())
        .containsExactly(
            WorkflowStatus.AWAITING_REVIEW,
            WorkflowStatus.FLAGGED,
            WorkflowStatus.AWAITING_APPROVAL,
            WorkflowStatus.FILED,
            WorkflowStatus.REJECTED);
  }

  @Test
  void inputModalityEnumExposesExactlyTextAndPdf() {
    assertThat(InputModality.values()).containsExactly(InputModality.TEXT, InputModality.PDF);
  }

  @Test
  void stageDefinitionRoleNullableForReviewAndTerminalNonNullForApproval() {
    StageDefinition review =
        new StageDefinition(
            "Review", "Review", StageKind.REVIEW, WorkflowStatus.AWAITING_REVIEW, null);
    StageDefinition approval =
        new StageDefinition(
            "Manager Approval",
            "Manager Approval",
            StageKind.APPROVAL,
            WorkflowStatus.AWAITING_APPROVAL,
            "manager");
    StageDefinition filed =
        new StageDefinition("Filed", "Filed", StageKind.TERMINAL, WorkflowStatus.FILED, null);
    StageDefinition rejected =
        new StageDefinition(
            "Rejected", "Rejected", StageKind.TERMINAL, WorkflowStatus.REJECTED, null);

    assertThat(review.role()).isNull();
    assertThat(approval.role()).isEqualTo("manager");
    assertThat(filed.role()).isNull();
    assertThat(rejected.role()).isNull();
  }

  @Test
  void stageKindEnumHasReviewApprovalTerminal() {
    assertThat(StageKind.values())
        .containsExactly(StageKind.REVIEW, StageKind.APPROVAL, StageKind.TERMINAL);
  }

  @Test
  void guardOpEnumHasEqAndNeq() {
    assertThat(GuardOp.values()).containsExactly(GuardOp.EQ, GuardOp.NEQ);
  }
}
