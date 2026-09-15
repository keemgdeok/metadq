package io.github.keemgdeok.metadq.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.keemgdeok.metadq.core.RuleSpec.LastCommitAgeRule;
import io.github.keemgdeok.metadq.core.RuleSpec.NullRatioRule;
import io.github.keemgdeok.metadq.core.RuleSpec.RowCountRule;
import io.github.keemgdeok.metadq.core.RuleSpec.SchemaColumnRule;
import io.github.keemgdeok.metadq.core.TableEvidence.ColumnEvidence;
import io.github.keemgdeok.metadq.core.TableEvidence.MetricCoverage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleEvaluatorTest {
  private static final Instant SNAPSHOT_TIME = Instant.parse("2026-09-13T10:00:00Z");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-13T11:00:00Z"), ZoneOffset.UTC);

  @Test
  void rowCountUsesInclusiveBounds() {
    TableEvidence evidence = evidence(100, true, false, false, false);

    assertEquals(Status.PASS, evaluate(new RowCountRule("rows", 100L, 100L), evidence).status());
    assertEquals(Status.FAIL, evaluate(new RowCountRule("rows", 101L, null), evidence).status());
  }

  @Test
  void rowCountIsUnknownForDeletesOrInconsistentMetrics() {
    RuleResult deletes =
        evaluate(new RowCountRule("rows", 1L, null), evidence(100, true, true, false, false));
    assertEquals(Status.UNKNOWN, deletes.status());
    assertEquals(ReasonCode.ACTIVE_DELETE_FILES, deletes.reasonCode());

    RuleResult inconsistent =
        evaluate(new RowCountRule("rows", 1L, null), evidence(100, true, false, true, false));
    assertEquals(Status.UNKNOWN, inconsistent.status());
    assertEquals(ReasonCode.INCONSISTENT_METRICS, inconsistent.reasonCode());
  }

  @Test
  void nullRatioHandlesCompleteMissingRequiredAndEmptyEvidence() {
    NullRatioRule rule = new NullRatioRule("nulls", "user_id", 0.05);
    assertEquals(Status.PASS, evaluate(rule, evidence(100, true, false, false, false)).status());
    assertEquals(
        Status.FAIL,
        evaluate(
                new NullRatioRule("nulls", "user_id", 0.04),
                evidence(100, true, false, false, false))
            .status());

    RuleResult missingMetrics = evaluate(rule, evidence(100, false, false, false, false));
    assertEquals(Status.UNKNOWN, missingMetrics.status());
    assertEquals(ReasonCode.MISSING_METRICS, missingMetrics.reasonCode());

    RuleResult required = evaluate(rule, evidence(100, false, false, false, true));
    assertEquals(Status.PASS, required.status());
    assertEquals("null_ratio=0, null_count=0", required.observed());

    RuleResult empty = evaluate(rule, evidence(0, true, false, false, false));
    assertEquals(Status.UNKNOWN, empty.status());
    assertEquals(ReasonCode.EMPTY_TABLE, empty.reasonCode());
  }

  @Test
  void missingColumnFailsAndDeleteStateMakesNullRatioUnknown() {
    RuleResult missing =
        evaluate(
            new NullRatioRule("missing", "not_here", 0), evidence(100, true, false, false, false));
    assertEquals(Status.FAIL, missing.status());
    assertEquals(ReasonCode.MISSING_COLUMN, missing.reasonCode());

    RuleResult deletes =
        evaluate(
            new NullRatioRule("nulls", "user_id", 0.1), evidence(100, true, true, false, false));
    assertEquals(Status.UNKNOWN, deletes.status());
    assertEquals(ReasonCode.ACTIVE_DELETE_FILES, deletes.reasonCode());
  }

  @Test
  void nestedColumnNullRatioIsUnknown() {
    TableEvidence base = evidence(100, true, false, false, false);
    Map<String, ColumnEvidence> columns = new LinkedHashMap<>(base.columns());
    columns.put(
        "payload",
        new ColumnEvidence(
            3,
            "payload",
            "struct<value: string>",
            false,
            false,
            false,
            new MetricCoverage(2, 0, 0, 0, 0),
            0,
            false,
            false));
    TableEvidence withNested =
        new TableEvidence(
            base.tableName(),
            base.formatVersion(),
            base.snapshotId(),
            base.snapshotTimestamp(),
            base.snapshotOperation(),
            base.dataFileCount(),
            base.referencedDataBytes(),
            base.metadataRecordCount(),
            base.deleteFileCounts(),
            columns,
            base.inconsistentMetrics());

    RuleResult result = evaluate(new NullRatioRule("nulls", "payload", 0.1), withNested);

    assertEquals(Status.UNKNOWN, result.status());
    assertEquals(ReasonCode.UNSUPPORTED_COLUMN_TYPE, result.reasonCode());
  }

  @Test
  void lastCommitAgeUsesInjectedClockAndHandlesNoSnapshot() {
    TableEvidence evidence = evidence(100, true, false, false, false);
    assertEquals(
        Status.PASS,
        evaluate(new LastCommitAgeRule("recent", Duration.ofHours(1)), evidence).status());
    assertEquals(
        Status.FAIL,
        evaluate(new LastCommitAgeRule("recent", Duration.ofMinutes(59)), evidence).status());

    TableEvidence noSnapshot = withoutSnapshot(evidence(0, true, false, false, false));
    RuleResult unknown = evaluate(new LastCommitAgeRule("recent", Duration.ofHours(1)), noSnapshot);
    assertEquals(Status.UNKNOWN, unknown.status());
    assertEquals(ReasonCode.NO_CURRENT_SNAPSHOT, unknown.reasonCode());
  }

  @Test
  void schemaColumnChecksTypeAndNullability() {
    TableEvidence evidence = evidence(100, true, false, false, false);
    assertEquals(
        Status.PASS,
        evaluate(new SchemaColumnRule("schema", "user_id", "long", true), evidence).status());

    RuleResult typeMismatch =
        evaluate(new SchemaColumnRule("schema", "user_id", "string", true), evidence);
    assertEquals(Status.FAIL, typeMismatch.status());
    assertEquals(ReasonCode.TYPE_MISMATCH, typeMismatch.reasonCode());

    assertEquals(
        Status.FAIL,
        evaluate(new SchemaColumnRule("schema", "user_id", "long", false), evidence).status());
  }

  @Test
  void overallExitCodeUsesDocumentedPrecedence() {
    assertEquals(0, RuleEvaluator.exitCode(List.of(result(Status.PASS))));
    assertEquals(3, RuleEvaluator.exitCode(List.of(result(Status.PASS), result(Status.UNKNOWN))));
    assertEquals(1, RuleEvaluator.exitCode(List.of(result(Status.UNKNOWN), result(Status.FAIL))));
    assertEquals(2, RuleEvaluator.exitCode(List.of(result(Status.FAIL), result(Status.ERROR))));
  }

  private static RuleResult evaluate(RuleSpec rule, TableEvidence evidence) {
    return RuleEvaluator.evaluate(rule, evidence, CLOCK);
  }

  private static RuleResult result(Status status) {
    return new RuleResult("id", "type", status, null, null, null, 1L, CLOCK.instant());
  }

  private static TableEvidence evidence(
      long rows, boolean completeNulls, boolean deletes, boolean inconsistent, boolean required) {
    Map<String, ColumnEvidence> columns = new LinkedHashMap<>();
    columns.put(
        "user_id",
        new ColumnEvidence(
            2,
            "user_id",
            "long",
            true,
            required,
            false,
            new MetricCoverage(2, 2, completeNulls ? 2 : 1, 0, 2),
            5,
            completeNulls,
            inconsistent));
    return new TableEvidence(
        "analytics.events",
        2,
        123L,
        SNAPSHOT_TIME,
        "append",
        2,
        2_048,
        rows,
        deletes ? Map.of("position_deletes", 1L) : Map.of(),
        columns,
        inconsistent);
  }

  private static TableEvidence withoutSnapshot(TableEvidence evidence) {
    return new TableEvidence(
        evidence.tableName(),
        evidence.formatVersion(),
        null,
        null,
        null,
        evidence.dataFileCount(),
        evidence.referencedDataBytes(),
        evidence.metadataRecordCount(),
        evidence.deleteFileCounts(),
        evidence.columns(),
        evidence.inconsistentMetrics());
  }
}
