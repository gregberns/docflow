package com.docflow.config.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.docflow.config.org.loader.ConfigLoader;
import com.docflow.config.org.validation.ConfigValidator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SeedFixturesTest {

  private static final String SEED_ROOT = "classpath:seed/";

  private final ConfigLoader loader = new ConfigLoader();
  private final ConfigValidator validator = new ConfigValidator();

  @Test
  void seedFixturesParseAndValidate() {
    OrgConfig config = loader.load(SEED_ROOT);

    assertThatCode(() -> validator.validate(config)).doesNotThrowAnyException();

    assertThat(config.organizations()).hasSize(3);
    assertThat(config.docTypes()).hasSize(9);
    assertThat(config.workflows()).hasSize(9);
  }

  @Test
  void riversideInvoiceDeclaresNineFieldsWithLineItemsArray() {
    OrgConfig config = loader.load(SEED_ROOT);

    DocTypeDefinition invoice = lookupDocType(config, "riverside-bistro", "invoice");

    assertThat(invoice.fields()).hasSize(9);

    FieldDefinition lineItems =
        invoice.fields().stream()
            .filter(f -> "lineItems".equals(f.name()))
            .findFirst()
            .orElseThrow();

    assertThat(lineItems.type()).isEqualTo(FieldType.ARRAY);
    ArrayItemSchema itemSchema = lineItems.itemSchema();
    assertThat(itemSchema).isNotNull();
    assertThat(itemSchema.fields())
        .extracting(FieldDefinition::name)
        .containsExactly("description", "quantity", "unitPrice", "total");
  }

  @Test
  void lienWaiverWorkflowHasInverseGuardedReviewApprovePair() {
    OrgConfig config = loader.load(SEED_ROOT);

    WorkflowDefinition waiver =
        config.workflows().stream()
            .filter(
                w ->
                    "ironworks-construction".equals(w.organizationId())
                        && "lien-waiver".equals(w.documentTypeId()))
            .findFirst()
            .orElseThrow();

    List<TransitionDefinition> reviewApprove =
        waiver.transitions().stream()
            .filter(
                t ->
                    "Review".equals(t.from())
                        && t.action() == TransitionAction.APPROVE
                        && t.guard() != null)
            .toList();

    assertThat(reviewApprove).hasSize(2);
    assertThat(reviewApprove)
        .allSatisfy(t -> assertThat(t.guard().field()).isEqualTo("waiverType"))
        .allSatisfy(t -> assertThat(t.guard().value()).isEqualTo("unconditional"));
    assertThat(reviewApprove)
        .extracting(t -> t.guard().op())
        .containsExactlyInAnyOrder(GuardOp.EQ, GuardOp.NEQ);
  }

  @Test
  void inputModalityIsPdfForNestedArrayDocTypesAndTextForTheRest() {
    OrgConfig config = loader.load(SEED_ROOT);

    Map<String, InputModality> modalityByKey =
        config.docTypes().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    dt -> dt.organizationId() + "/" + dt.id(), DocTypeDefinition::inputModality));

    assertThat(modalityByKey)
        .containsEntry("riverside-bistro/invoice", InputModality.PDF)
        .containsEntry("riverside-bistro/expense-report", InputModality.PDF)
        .containsEntry("pinnacle-legal/expense-report", InputModality.PDF)
        .containsEntry("ironworks-construction/invoice", InputModality.PDF)
        .containsEntry("riverside-bistro/receipt", InputModality.TEXT)
        .containsEntry("pinnacle-legal/invoice", InputModality.TEXT)
        .containsEntry("pinnacle-legal/retainer-agreement", InputModality.TEXT)
        .containsEntry("ironworks-construction/change-order", InputModality.TEXT)
        .containsEntry("ironworks-construction/lien-waiver", InputModality.TEXT);
  }

  private static DocTypeDefinition lookupDocType(OrgConfig config, String orgId, String docTypeId) {
    return config.docTypes().stream()
        .filter(dt -> orgId.equals(dt.organizationId()) && docTypeId.equals(dt.id()))
        .findFirst()
        .orElseThrow();
  }
}
