package com.docflow.c3.pipeline;

interface PipelineStep {

  String stepName();

  StepResult execute(PipelineContext context);
}
