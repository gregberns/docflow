package com.docflow.c3.pipeline;

sealed interface StepResult {

  record Success() implements StepResult {}

  record Failure(String message) implements StepResult {}
}
