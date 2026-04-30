package com.docflow.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.docflow.api.error.ErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.InvalidTypeIdException;
import tools.jackson.databind.json.JsonMapper;

class ActionRequestDeserializationTest {

  private final ObjectMapper mapper = JsonMapper.builder().build();

  @Test
  void approveRoundTrip() throws Exception {
    String json = "{\"action\":\"Approve\"}";
    ActionRequest parsed = mapper.readValue(json, ActionRequest.class);
    assertInstanceOf(ActionRequest.Approve.class, parsed);
    assertEquals(json, mapper.writeValueAsString(parsed));
    assertEquals(new ActionRequest.Approve(), parsed);
  }

  @Test
  void rejectRoundTrip() throws Exception {
    String json = "{\"action\":\"Reject\"}";
    ActionRequest parsed = mapper.readValue(json, ActionRequest.class);
    assertInstanceOf(ActionRequest.Reject.class, parsed);
    assertEquals(json, mapper.writeValueAsString(parsed));
    assertEquals(new ActionRequest.Reject(), parsed);
  }

  @Test
  void flagRoundTrip() throws Exception {
    String json = "{\"action\":\"Flag\",\"comment\":\"Totals don't match line items.\"}";
    ActionRequest parsed = mapper.readValue(json, ActionRequest.class);
    assertInstanceOf(ActionRequest.Flag.class, parsed);
    ActionRequest.Flag flag = (ActionRequest.Flag) parsed;
    assertEquals("Totals don't match line items.", flag.comment());
    assertEquals(json, mapper.writeValueAsString(parsed));
    assertEquals(new ActionRequest.Flag("Totals don't match line items."), parsed);
  }

  @Test
  void resolveRoundTrip() throws Exception {
    String json = "{\"action\":\"Resolve\"}";
    ActionRequest parsed = mapper.readValue(json, ActionRequest.class);
    assertInstanceOf(ActionRequest.Resolve.class, parsed);
    assertEquals(json, mapper.writeValueAsString(parsed));
    assertEquals(new ActionRequest.Resolve(), parsed);
  }

  @Test
  void unknownDiscriminatorThrows() {
    String json = "{\"action\":\"unknown\"}";
    assertThrows(InvalidTypeIdException.class, () -> mapper.readValue(json, ActionRequest.class));
  }

  @Test
  void errorCodeCountIsExactlyThirteen() {
    assertEquals(16, ErrorCode.values().length);
  }

  @Test
  void errorCodeHttpStatusMappings() {
    assertEquals(404, ErrorCode.UNKNOWN_ORGANIZATION.httpStatus());
    assertEquals(404, ErrorCode.UNKNOWN_DOCUMENT.httpStatus());
    assertEquals(404, ErrorCode.UNKNOWN_PROCESSING_DOCUMENT.httpStatus());
    assertEquals(404, ErrorCode.UNKNOWN_DOC_TYPE.httpStatus());
    assertEquals(404, ErrorCode.NOT_FOUND.httpStatus());
    assertEquals(415, ErrorCode.UNSUPPORTED_MEDIA_TYPE.httpStatus());
    assertEquals(400, ErrorCode.INVALID_FILE.httpStatus());
    assertEquals(400, ErrorCode.VALIDATION_FAILED.httpStatus());
    assertEquals(409, ErrorCode.INVALID_ACTION.httpStatus());
    assertEquals(409, ErrorCode.REEXTRACTION_IN_PROGRESS.httpStatus());
    assertEquals(409, ErrorCode.CONCURRENT_MODIFICATION.httpStatus());
    assertEquals(502, ErrorCode.LLM_UNAVAILABLE.httpStatus());
    assertEquals(504, ErrorCode.LLM_TIMEOUT.httpStatus());
    assertEquals(502, ErrorCode.LLM_PROTOCOL_ERROR.httpStatus());
    assertEquals(422, ErrorCode.LLM_SCHEMA_VIOLATION.httpStatus());
    assertEquals(500, ErrorCode.INTERNAL_ERROR.httpStatus());
  }
}
