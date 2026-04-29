package com.docflow.c3.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.services.blocking.MessageService;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmCallExecutorTest {

  private static final ToolSchema CLASSIFY_TOOL =
      new ToolSchema("select_doc_type", "{\"type\":\"object\"}");

  private AnthropicClient client;
  private MessageService messageService;
  private LlmCallExecutor executor;
  private MessageCreateParams params;

  @BeforeEach
  void setUp() {
    client = mock(AnthropicClient.class);
    messageService = mock(MessageService.class);
    when(client.messages()).thenReturn(messageService);
    executor = new LlmCallExecutor(client);
    params =
        MessageCreateParams.builder()
            .model("claude-sonnet-4-6")
            .maxTokens(512)
            .addUserMessage("hi")
            .build();
  }

  @Test
  void rateLimit429MapsToLlmUnavailable() {
    when(messageService.create(any(MessageCreateParams.class)))
        .thenThrow(
            RateLimitException.builder()
                .headers(Headers.builder().build())
                .body(JsonValue.from(""))
                .build());

    assertThatThrownBy(() -> executor.execute(params, CLASSIFY_TOOL))
        .isInstanceOf(LlmUnavailable.class)
        .hasMessageContaining("429");
  }

  @Test
  void serverError503MapsToLlmUnavailable() {
    when(messageService.create(any(MessageCreateParams.class)))
        .thenThrow(
            InternalServerException.builder()
                .statusCode(503)
                .headers(Headers.builder().build())
                .body(JsonValue.from(""))
                .build());

    assertThatThrownBy(() -> executor.execute(params, CLASSIFY_TOOL))
        .isInstanceOf(LlmUnavailable.class)
        .hasMessageContaining("503");
  }

  @Test
  void socketTimeoutMapsToLlmTimeout() {
    AnthropicIoException io =
        new AnthropicIoException("read timed out", new SocketTimeoutException("read timed out"));
    when(messageService.create(any(MessageCreateParams.class))).thenThrow(io);

    assertThatThrownBy(() -> executor.execute(params, CLASSIFY_TOOL))
        .isInstanceOf(LlmTimeout.class);
  }

  @Test
  void nonTimeoutIoExceptionMapsToLlmUnavailable() {
    AnthropicIoException io =
        new AnthropicIoException("connection reset", new IOException("reset"));
    when(messageService.create(any(MessageCreateParams.class))).thenThrow(io);

    assertThatThrownBy(() -> executor.execute(params, CLASSIFY_TOOL))
        .isInstanceOf(LlmUnavailable.class);
  }

  @Test
  void responseWithoutToolUseBlockMapsToLlmProtocolError() {
    Message message = mock(Message.class);
    TextBlock textBlock = mock(TextBlock.class);
    ContentBlock contentBlock = ContentBlock.ofText(textBlock);
    when(message.content()).thenReturn(List.of(contentBlock));
    when(messageService.create(any(MessageCreateParams.class))).thenReturn(message);

    assertThatThrownBy(() -> executor.execute(params, CLASSIFY_TOOL))
        .isInstanceOf(LlmProtocolError.class)
        .hasMessageContaining(CLASSIFY_TOOL.toolName());
  }

  @Test
  void responseWithToolUseBlockReturnsInputJsonValue() {
    JsonValue input = JsonValue.from(Map.of("docType", "invoice"));
    ToolUseBlock toolUseBlock =
        ToolUseBlock.builder()
            .id("toolu_test")
            .name(CLASSIFY_TOOL.toolName())
            .type(JsonValue.from("tool_use"))
            .input(input)
            .build();
    Message message = mock(Message.class);
    when(message.content()).thenReturn(List.of(ContentBlock.ofToolUse(toolUseBlock)));
    when(messageService.create(any(MessageCreateParams.class))).thenReturn(message);

    JsonValue result = executor.execute(params, CLASSIFY_TOOL);

    assertThat(result).isNotNull();
  }
}
