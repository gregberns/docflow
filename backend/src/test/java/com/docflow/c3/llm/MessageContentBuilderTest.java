package com.docflow.c3.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.docflow.c3.llm.MessageContentBuilder.InputModality;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageContentBuilderTest {

  private static final String MODEL = "claude-sonnet-4-6";
  private static final String SYSTEM_PROMPT = "you are a classifier";
  private static final ToolSchema CLASSIFY_SCHEMA =
      new ToolSchema(
          "select_doc_type",
          "{\"type\":\"object\","
              + "\"properties\":{\"docType\":{\"type\":\"string\",\"enum\":[\"invoice\"]}},"
              + "\"required\":[\"docType\"]}");

  private final MessageContentBuilder builder = new MessageContentBuilder();

  @Test
  void textModalityProducesSingleTextBlock() {
    MessageCreateParams params =
        builder.build(
            MODEL, SYSTEM_PROMPT, CLASSIFY_SCHEMA, InputModality.TEXT, 512, null, "raw text body");

    assertThat(params.maxTokens()).isEqualTo(512L);
    assertThat(params.system()).isPresent();

    List<MessageParam> messages = params.messages();
    assertThat(messages).hasSize(1);
    List<ContentBlockParam> blocks = messages.get(0).content().asBlockParams();
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).isText()).isTrue();
    assertThat(blocks.get(0).asText().text()).isEqualTo("raw text body");
  }

  @Test
  void pdfModalityProducesDocumentBlockWithBase64Source() {
    byte[] pdfBytes = "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8);
    String expectedBase64 = Base64.getEncoder().encodeToString(pdfBytes);

    MessageCreateParams params =
        builder.build(
            MODEL, SYSTEM_PROMPT, CLASSIFY_SCHEMA, InputModality.PDF, 2048, pdfBytes, null);

    List<ContentBlockParam> blocks = params.messages().get(0).content().asBlockParams();
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).isDocument()).isTrue();
    assertThat(blocks.get(0).asDocument().source().isBase64()).isTrue();
    assertThat(blocks.get(0).asDocument().source().asBase64().data()).isEqualTo(expectedBase64);
  }

  @Test
  void toolChoiceForcesNamedTool() {
    MessageCreateParams params =
        builder.build(MODEL, SYSTEM_PROMPT, CLASSIFY_SCHEMA, InputModality.TEXT, 512, null, "text");

    assertThat(params.toolChoice()).isPresent();
    assertThat(params.toolChoice().get().isTool()).isTrue();
    assertThat(params.toolChoice().get().asTool().name()).isEqualTo("select_doc_type");
  }

  @Test
  void toolListContainsNamedToolWithInputSchema() {
    MessageCreateParams params =
        builder.build(MODEL, SYSTEM_PROMPT, CLASSIFY_SCHEMA, InputModality.TEXT, 512, null, "text");

    assertThat(params.tools()).isPresent();
    assertThat(params.tools().get()).hasSize(1);
    assertThat(params.tools().get().get(0).isTool()).isTrue();
    assertThat(params.tools().get().get(0).asTool().name()).isEqualTo("select_doc_type");
    assertThat(params.tools().get().get(0).asTool().inputSchema().required()).isPresent();
    assertThat(params.tools().get().get(0).asTool().inputSchema().required().get())
        .containsExactly("docType");
  }

  @Test
  void textModalityRejectsBlankText() {
    assertThatThrownBy(
            () ->
                builder.build(
                    MODEL, SYSTEM_PROMPT, CLASSIFY_SCHEMA, InputModality.TEXT, 512, null, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void pdfModalityRejectsEmptyBytes() {
    assertThatThrownBy(
            () ->
                builder.build(
                    MODEL,
                    SYSTEM_PROMPT,
                    CLASSIFY_SCHEMA,
                    InputModality.PDF,
                    2048,
                    new byte[0],
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
