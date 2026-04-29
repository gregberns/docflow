package com.docflow.c3.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ToolUseBlock;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class LlmCallExecutor {

  private final AnthropicClient client;

  public LlmCallExecutor(AnthropicClient client) {
    this.client = client;
  }

  public JsonValue execute(MessageCreateParams params, ToolSchema tool) {
    if (params == null) {
      throw new IllegalArgumentException("params must not be null");
    }
    if (tool == null) {
      throw new IllegalArgumentException("tool must not be null");
    }

    Message response;
    try {
      response = client.messages().create(params);
    } catch (AnthropicServiceException e) {
      throw new LlmUnavailable("anthropic service returned status " + e.statusCode(), e);
    } catch (AnthropicIoException e) {
      if (isTimeout(e)) {
        throw new LlmTimeout("anthropic call timed out", e);
      }
      throw new LlmUnavailable("anthropic i/o failure", e);
    } catch (AnthropicException e) {
      throw new LlmUnavailable("anthropic call failed", e);
    }

    Optional<ToolUseBlock> toolUse =
        response.content().stream().flatMap(cb -> cb.toolUse().stream()).findFirst();
    if (toolUse.isEmpty()) {
      throw new LlmProtocolError(
          "anthropic response did not contain a tool_use block for tool '" + tool.toolName() + "'");
    }
    // TODO C3.7/C3.8: schema validation against tool.inputSchemaJson is owned by the consumer.
    return toolUse.get()._input();
  }

  private static boolean isTimeout(Throwable t) {
    Throwable cur = t;
    while (cur != null) {
      if (cur instanceof SocketTimeoutException || cur instanceof InterruptedIOException) {
        return true;
      }
      cur = cur.getCause();
    }
    return false;
  }
}
