package com.docflow.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;

class ScenarioRunnerIT extends AbstractScenarioIT {

  @Autowired private ScenarioContext scenarioContext;
  @Autowired private ScenarioRecorder scenarioRecorder;

  static Stream<ScenarioFixture> scenarios() {
    return Stream.empty();
  }

  @Test
  void contextLoadsUnderScenarioProfile() {
    assertThat(scenarioContext).isNotNull();
    assertThat(scenarioRecorder).isNotNull();
  }

  @TestFactory
  Stream<DynamicTest> runScenarios() {
    return scenarios()
        .map(
            fixture ->
                DynamicTest.dynamicTest(
                    fixture.scenarioId(),
                    () -> {
                      throw new UnsupportedOperationException(
                          "scenario execution arrives in downstream tickets");
                    }));
  }
}
