package com.docflow.workflow;

import com.docflow.config.catalog.GuardView;
import com.docflow.config.catalog.TransitionView;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.config.catalog.WorkflowView;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record TransitionResolver(WorkflowCatalog catalog) {

  public sealed interface Result permits Result.Match, Result.Invalid {
    record Match(TransitionView transition) implements Result {}

    record Invalid(WorkflowError.InvalidAction error) implements Result {}
  }

  public Result resolve(
      String organizationId,
      String docTypeId,
      String currentStageId,
      WorkflowAction action,
      Map<String, Object> extractedFields) {
    String actionName = actionName(action);
    Optional<WorkflowView> view = catalog.getWorkflow(organizationId, docTypeId);
    if (view.isEmpty()) {
      return new Result.Invalid(new WorkflowError.InvalidAction(currentStageId, actionName));
    }

    Map<String, Object> fields = extractedFields == null ? Map.of() : extractedFields;
    for (TransitionView transition : view.get().transitions()) {
      if (!transition.fromStage().equals(currentStageId)) {
        continue;
      }
      if (!transition.action().equals(actionName)) {
        continue;
      }
      if (evaluateGuard(transition.guard(), fields)) {
        return new Result.Match(transition);
      }
    }
    return new Result.Invalid(new WorkflowError.InvalidAction(currentStageId, actionName));
  }

  private static String actionName(WorkflowAction action) {
    return switch (action) {
      case WorkflowAction.Approve a -> {
        Objects.requireNonNull(a);
        yield "APPROVE";
      }
      case WorkflowAction.Reject r -> {
        Objects.requireNonNull(r);
        yield "REJECT";
      }
      case WorkflowAction.Flag f -> {
        Objects.requireNonNull(f);
        yield "FLAG";
      }
      case WorkflowAction.Resolve r -> {
        Objects.requireNonNull(r);
        yield "RESOLVE";
      }
    };
  }

  private static boolean evaluateGuard(GuardView guard, Map<String, Object> fields) {
    if (guard == null) {
      return true;
    }
    String op = guard.op();
    if (op == null) {
      return true;
    }
    return switch (op) {
      case "EQ" -> Objects.equals(stringify(fields.get(guard.field())), guard.value());
      case "NEQ" -> !Objects.equals(stringify(fields.get(guard.field())), guard.value());
      default -> false;
    };
  }

  private static String stringify(Object value) {
    return value == null ? null : value.toString();
  }
}
