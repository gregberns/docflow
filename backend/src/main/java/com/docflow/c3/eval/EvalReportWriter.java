package com.docflow.c3.eval;

import com.docflow.c3.eval.EvalScorer.AggregateScore;
import com.docflow.c3.eval.EvalScorer.SampleScore;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Renders eval results as markdown per spec §3.10: two top-line aggregate numbers and a per-sample
 * table. Pure — produces a String that the caller writes to disk.
 */
public final class EvalReportWriter {

  public String render(
      List<SampleScore> samples, AggregateScore aggregate, Instant runAt, boolean complete) {
    Objects.requireNonNull(samples, "samples");
    Objects.requireNonNull(aggregate, "aggregate");
    Objects.requireNonNull(runAt, "runAt");

    StringBuilder sb = new StringBuilder(1024);
    sb.append("# DocFlow C3 Eval Report\n\n");
    sb.append("- Run at: `").append(runAt).append("`\n");
    sb.append("- Status: ").append(complete ? "complete" : "INCOMPLETE").append('\n');
    sb.append("- Samples: ").append(aggregate.classifyTotal()).append("\n\n");

    sb.append("## Aggregate scores\n\n");
    sb.append("- Classification accuracy: ")
        .append(aggregate.classifyHit())
        .append('/')
        .append(aggregate.classifyTotal())
        .append(" (")
        .append(formatPct(aggregate.classifyAccuracy()))
        .append(")\n");
    sb.append("- Extraction field accuracy: ")
        .append(aggregate.fieldsMatched())
        .append('/')
        .append(aggregate.fieldsTotal())
        .append(" (")
        .append(formatPct(aggregate.fieldAccuracy()))
        .append(")\n\n");

    sb.append("## Per-sample results\n\n");
    sb.append("| Sample | Expected | Predicted | Classify | Fields |\n");
    sb.append("|---|---|---|---|---|\n");
    for (SampleScore s : samples) {
      sb.append("| `")
          .append(s.samplePath())
          .append("` | ")
          .append(s.expectedDocType())
          .append(" | ")
          .append(s.predictedDocType() == null ? "(none)" : s.predictedDocType())
          .append(" | ")
          .append(s.classifyHit() ? "OK" : "MISS")
          .append(" | ")
          .append(s.fieldsMatched())
          .append('/')
          .append(s.fieldsTotal())
          .append(" |\n");
    }

    return sb.toString();
  }

  private static String formatPct(double ratio) {
    return String.format(Locale.ROOT, "%.1f%%", ratio * 100.0);
  }
}
