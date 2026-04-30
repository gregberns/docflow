package com.docflow.c3.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docflow.config.catalog.DocumentTypeSchemaView;
import com.docflow.config.catalog.FieldView;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolSchemaBuilderTest {

  private final ToolSchemaBuilder builder = new ToolSchemaBuilder();

  @Test
  void classifyToolNameIsSelectDocType() {
    ToolSchema schema = builder.buildClassifySchema("riverside-bistro", List.of("invoice"));
    assertThat(schema.toolName()).isEqualTo("select_doc_type");
  }

  @Test
  void classifySchemaUsesAllowedDocTypesInDeclarationOrder() {
    ToolSchema schema =
        builder.buildClassifySchema(
            "pinnacle-legal", List.of("retainer-agreement", "invoice", "expense-report"));
    assertThat(schema.inputSchemaJson())
        .isEqualTo(
            "{\"type\":\"object\","
                + "\"properties\":{\"docType\":{\"type\":\"string\","
                + "\"enum\":[\"retainer-agreement\",\"invoice\",\"expense-report\"]}},"
                + "\"required\":[\"docType\"]}");
  }

  @Test
  void classifyRejectsEmptyAllowedDocTypes() {
    assertThatThrownBy(() -> builder.buildClassifySchema("riverside-bistro", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void extractToolNameIsExtractPrefixedWithDocTypeId() {
    DocumentTypeSchemaView docType =
        new DocumentTypeSchemaView(
            "pinnacle-legal",
            "pinnacle_invoice",
            "Pinnacle Invoice",
            List.of(new FieldView("vendor", "STRING", true, null, null, null)));
    ToolSchema schema = builder.buildExtractSchema(docType);
    assertThat(schema.toolName()).isEqualTo("extract_pinnacle_invoice");
  }

  @Test
  void extractSchemaIsByteEqualOnRepeatedBuilds() {
    DocumentTypeSchemaView docType = sampleInvoiceDocType();

    String first = builder.buildExtractSchema(docType).inputSchemaJson();
    String second = builder.buildExtractSchema(docType).inputSchemaJson();

    assertThat(second).isEqualTo(first);
    assertThat(second.getBytes(StandardCharsets.UTF_8))
        .isEqualTo(first.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void extractSchemaIncludesOnlyRequiredFieldsInDeclarationOrder() {
    DocumentTypeSchemaView docType =
        new DocumentTypeSchemaView(
            "ironworks-construction",
            "change_order",
            "Change Order",
            List.of(
                new FieldView("vendor", "STRING", true, null, null, null),
                new FieldView("notes", "STRING", false, null, null, null),
                new FieldView("amount", "DECIMAL", true, null, null, null),
                new FieldView("approvedBy", "STRING", false, null, null, null),
                new FieldView("effectiveDate", "DATE", true, null, null, null)));

    String json = builder.buildExtractSchema(docType).inputSchemaJson();

    assertThat(json).contains("\"required\":[\"vendor\",\"amount\",\"effectiveDate\"]");
  }

  @Test
  void extractSchemaSerializesEnumFieldAsStringWithEnumValuesInDeclarationOrder() {
    DocumentTypeSchemaView docType =
        new DocumentTypeSchemaView(
            "riverside-bistro",
            "receipt",
            "Receipt",
            List.of(
                new FieldView(
                    "paymentMethod",
                    "ENUM",
                    true,
                    List.of("cash", "credit", "debit", "check"),
                    null,
                    null)));

    String json = builder.buildExtractSchema(docType).inputSchemaJson();

    assertThat(json)
        .contains(
            "\"paymentMethod\":{\"type\":\"string\","
                + "\"enum\":[\"cash\",\"credit\",\"debit\",\"check\"]}");
  }

  @Test
  void extractSchemaSerializesArrayOfObjectsWithRecursedItemFields() {
    DocumentTypeSchemaView docType =
        new DocumentTypeSchemaView(
            "ironworks-construction",
            "invoice",
            "Invoice",
            List.of(
                new FieldView("vendor", "STRING", true, null, null, null),
                new FieldView(
                    "materials",
                    "ARRAY",
                    true,
                    null,
                    null,
                    List.of(
                        new FieldView("item", "STRING", true, null, null, null),
                        new FieldView("quantity", "DECIMAL", true, null, null, null),
                        new FieldView("unitCost", "DECIMAL", false, null, null, null)))));

    String json = builder.buildExtractSchema(docType).inputSchemaJson();

    assertThat(json)
        .contains(
            "\"materials\":{\"type\":\"array\",\"items\":{\"type\":\"object\","
                + "\"properties\":{"
                + "\"item\":{\"type\":\"string\"},"
                + "\"quantity\":{\"type\":\"number\"},"
                + "\"unitCost\":{\"type\":\"number\"}},"
                + "\"required\":[\"item\",\"quantity\"]}}");
  }

  @Test
  void extractSchemaMapsDateAndDecimalAndStringTypes() {
    DocumentTypeSchemaView docType =
        new DocumentTypeSchemaView(
            "pinnacle-legal",
            "retainer_agreement",
            "Retainer Agreement",
            List.of(
                new FieldView("clientName", "STRING", true, null, null, null),
                new FieldView("retainerAmount", "DECIMAL", true, null, null, null),
                new FieldView("effectiveDate", "DATE", true, null, null, null)));

    String json = builder.buildExtractSchema(docType).inputSchemaJson();

    assertThat(json)
        .isEqualTo(
            "{\"type\":\"object\","
                + "\"properties\":{"
                + "\"clientName\":{\"type\":\"string\"},"
                + "\"retainerAmount\":{\"type\":\"number\"},"
                + "\"effectiveDate\":{\"type\":\"string\"}},"
                + "\"required\":[\"clientName\",\"retainerAmount\",\"effectiveDate\"]}");
  }

  @Test
  void extractRejectsEnumWithoutValues() {
    DocumentTypeSchemaView docType =
        new DocumentTypeSchemaView(
            "riverside-bistro",
            "receipt",
            "Receipt",
            List.of(new FieldView("paymentMethod", "ENUM", true, null, null, null)));

    assertThatThrownBy(() -> builder.buildExtractSchema(docType))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paymentMethod");
  }

  @Test
  void extractRejectsArrayWithoutItemFields() {
    DocumentTypeSchemaView docType =
        new DocumentTypeSchemaView(
            "ironworks-construction",
            "invoice",
            "Invoice",
            List.of(new FieldView("materials", "ARRAY", true, null, null, null)));

    assertThatThrownBy(() -> builder.buildExtractSchema(docType))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("materials");
  }

  @Test
  void extractRejectsUnsupportedFieldType() {
    DocumentTypeSchemaView docType =
        new DocumentTypeSchemaView(
            "riverside-bistro",
            "receipt",
            "Receipt",
            List.of(new FieldView("weirdField", "MYSTERY", true, null, null, null)));

    assertThatThrownBy(() -> builder.buildExtractSchema(docType))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MYSTERY");
  }

  private static DocumentTypeSchemaView sampleInvoiceDocType() {
    return new DocumentTypeSchemaView(
        "ironworks-construction",
        "invoice",
        "Invoice",
        List.of(
            new FieldView("vendor", "STRING", true, null, null, null),
            new FieldView("invoiceNumber", "STRING", true, null, null, null),
            new FieldView("invoiceDate", "DATE", true, null, null, null),
            new FieldView("amount", "DECIMAL", true, null, null, null),
            new FieldView(
                "materials",
                "ARRAY",
                true,
                null,
                null,
                List.of(
                    new FieldView("item", "STRING", true, null, null, null),
                    new FieldView("quantity", "DECIMAL", true, null, null, null),
                    new FieldView("unitCost", "DECIMAL", true, null, null, null)))));
  }
}
