package io.github.keemgdeok.metadq.output;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.keemgdeok.metadq.core.ReasonCode;
import io.github.keemgdeok.metadq.core.RuleResult;
import io.github.keemgdeok.metadq.core.Status;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheckRendererTest {
  private static final Instant EVALUATED_AT = Instant.parse("2026-09-15T00:00:00Z");

  @Test
  void jsonUsesSnakeCaseAndPreservesRuleOrder() {
    String output =
        new CheckRenderer()
            .render(
                "analytics.events",
                42L,
                EVALUATED_AT,
                List.of(pass("first"), unknown("second")),
                OutputFormat.JSON);

    assertTrue(output.contains("\"snapshot_id\" : 42"));
    assertTrue(output.contains("\"rule_id\" : \"first\""));
    assertTrue(output.contains("\"reason_code\" : \"MISSING_METRICS\""));
    assertTrue(output.indexOf("\"first\"") < output.indexOf("\"second\""));
    assertFalse(output.contains("reasonCode"));
  }

  @Test
  void textPreservesRuleOrderAndShowsStableReason() {
    String output =
        new CheckRenderer()
            .render(
                "analytics.events",
                42L,
                EVALUATED_AT,
                List.of(pass("first"), unknown("second")),
                OutputFormat.TEXT);

    assertTrue(output.indexOf("first") < output.indexOf("second"));
    assertTrue(output.contains("row_count=10"));
    assertTrue(output.contains("reason=MISSING_METRICS"));
  }

  private static RuleResult pass(String id) {
    return new RuleResult(
        id,
        "table.row_count",
        Status.PASS,
        "row_count=10",
        "row_count>=1",
        null,
        42L,
        EVALUATED_AT);
  }

  private static RuleResult unknown(String id) {
    return new RuleResult(
        id,
        "column.null_ratio",
        Status.UNKNOWN,
        "null_metrics=1/2",
        "null_ratio<=0",
        ReasonCode.MISSING_METRICS,
        42L,
        EVALUATED_AT);
  }
}
