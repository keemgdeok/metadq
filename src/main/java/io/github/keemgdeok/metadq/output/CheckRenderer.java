package io.github.keemgdeok.metadq.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.keemgdeok.metadq.core.RuleResult;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public final class CheckRenderer {
  public String render(
      String tableName,
      Long snapshotId,
      Instant evaluatedAt,
      List<RuleResult> results,
      OutputFormat format) {
    if (format == OutputFormat.JSON) {
      try {
        return JsonSupport.mapper()
            .writeValueAsString(new CheckDocument(1, tableName, snapshotId, evaluatedAt, results));
      } catch (JsonProcessingException exception) {
        throw new IllegalStateException("Could not render check JSON", exception);
      }
    }

    StringBuilder output = new StringBuilder();
    for (RuleResult result : results) {
      String detail =
          result.reasonCode() != null
              ? "reason=" + result.reasonCode()
              : result.observed() == null ? "" : result.observed();
      output.append(
          String.format(Locale.ROOT, "%-8s %-28s %s%n", result.status(), result.ruleId(), detail));
    }
    return output.toString().stripTrailing();
  }

  private record CheckDocument(
      int version, String table, Long snapshotId, Instant evaluatedAt, List<RuleResult> results) {}
}
