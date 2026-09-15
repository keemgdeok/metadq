package io.github.keemgdeok.metadq.core;

import io.github.keemgdeok.metadq.core.RuleSpec.LastCommitAgeRule;
import io.github.keemgdeok.metadq.core.RuleSpec.NullRatioRule;
import io.github.keemgdeok.metadq.core.RuleSpec.RowCountRule;
import io.github.keemgdeok.metadq.core.RuleSpec.SchemaColumnRule;
import io.github.keemgdeok.metadq.core.TableEvidence.ColumnEvidence;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class RuleEvaluator {
  private RuleEvaluator() {}

  public static RuleResult evaluate(RuleSpec rule, TableEvidence evidence, Clock clock) {
    Instant evaluatedAt = clock.instant();
    if (rule instanceof RowCountRule rowCountRule) {
      return evaluateRowCount(rowCountRule, evidence, evaluatedAt);
    }
    if (rule instanceof NullRatioRule nullRatioRule) {
      return evaluateNullRatio(nullRatioRule, evidence, evaluatedAt);
    }
    if (rule instanceof LastCommitAgeRule lastCommitAgeRule) {
      return evaluateLastCommitAge(lastCommitAgeRule, evidence, evaluatedAt);
    }
    return evaluateSchemaColumn((SchemaColumnRule) rule, evidence, evaluatedAt);
  }

  public static int exitCode(List<RuleResult> results) {
    if (results.stream().anyMatch(result -> result.status() == Status.ERROR)) {
      return 2;
    }
    if (results.stream().anyMatch(result -> result.status() == Status.FAIL)) {
      return 1;
    }
    if (results.stream().anyMatch(result -> result.status() == Status.UNKNOWN)) {
      return 3;
    }
    return 0;
  }

  private static RuleResult evaluateRowCount(
      RowCountRule rule, TableEvidence evidence, Instant evaluatedAt) {
    String expected = range(rule.min(), rule.max());
    if (evidence.inconsistentMetrics()) {
      return result(
          rule,
          Status.UNKNOWN,
          null,
          expected,
          ReasonCode.INCONSISTENT_METRICS,
          evidence,
          evaluatedAt);
    }
    if (evidence.hasApplicableDeletes()) {
      return result(
          rule,
          Status.UNKNOWN,
          "metadata_record_count=" + evidence.metadataRecordCount(),
          expected,
          ReasonCode.ACTIVE_DELETE_FILES,
          evidence,
          evaluatedAt);
    }

    long count = evidence.metadataRecordCount();
    boolean aboveMin = rule.min() == null || count >= rule.min();
    boolean belowMax = rule.max() == null || count <= rule.max();
    return result(
        rule,
        aboveMin && belowMax ? Status.PASS : Status.FAIL,
        "row_count=" + count,
        expected,
        null,
        evidence,
        evaluatedAt);
  }

  private static RuleResult evaluateNullRatio(
      NullRatioRule rule, TableEvidence evidence, Instant evaluatedAt) {
    String expected = "null_ratio<=" + decimal(rule.max());
    ColumnEvidence column = evidence.columns().get(rule.column());
    if (column == null) {
      return result(
          rule,
          Status.FAIL,
          "column=missing",
          expected,
          ReasonCode.MISSING_COLUMN,
          evidence,
          evaluatedAt);
    }
    if (!column.primitive()) {
      return result(
          rule,
          Status.UNKNOWN,
          "column_type=" + column.dataType(),
          expected,
          ReasonCode.UNSUPPORTED_COLUMN_TYPE,
          evidence,
          evaluatedAt);
    }
    if (evidence.inconsistentMetrics() || column.inconsistentMetrics()) {
      return result(
          rule,
          Status.UNKNOWN,
          null,
          expected,
          ReasonCode.INCONSISTENT_METRICS,
          evidence,
          evaluatedAt);
    }
    if (evidence.hasApplicableDeletes()) {
      return result(
          rule,
          Status.UNKNOWN,
          null,
          expected,
          ReasonCode.ACTIVE_DELETE_FILES,
          evidence,
          evaluatedAt);
    }
    if (evidence.metadataRecordCount() == 0) {
      return result(
          rule, Status.UNKNOWN, null, expected, ReasonCode.EMPTY_TABLE, evidence, evaluatedAt);
    }
    if (!column.required() && !column.nullCountsCompleteForRows()) {
      return result(
          rule,
          Status.UNKNOWN,
          coverageObservation(column),
          expected,
          ReasonCode.MISSING_METRICS,
          evidence,
          evaluatedAt);
    }

    long nullCount = column.required() ? 0 : column.nullCount();
    double ratio = (double) nullCount / evidence.metadataRecordCount();
    return result(
        rule,
        ratio <= rule.max() ? Status.PASS : Status.FAIL,
        "null_ratio=" + decimal(ratio) + ", null_count=" + nullCount,
        expected,
        null,
        evidence,
        evaluatedAt);
  }

  private static RuleResult evaluateLastCommitAge(
      LastCommitAgeRule rule, TableEvidence evidence, Instant evaluatedAt) {
    String expected = "commit_age<=" + compactDuration(rule.max());
    if (!evidence.hasCurrentSnapshot()) {
      return result(
          rule,
          Status.UNKNOWN,
          null,
          expected,
          ReasonCode.NO_CURRENT_SNAPSHOT,
          evidence,
          evaluatedAt);
    }

    Duration age = Duration.between(evidence.snapshotTimestamp(), evaluatedAt);
    if (age.isNegative()) {
      return result(
          rule,
          Status.UNKNOWN,
          "snapshot_timestamp=" + evidence.snapshotTimestamp(),
          expected,
          ReasonCode.INCONSISTENT_METRICS,
          evidence,
          evaluatedAt);
    }
    return result(
        rule,
        age.compareTo(rule.max()) <= 0 ? Status.PASS : Status.FAIL,
        "commit_age=" + compactDuration(age),
        expected,
        null,
        evidence,
        evaluatedAt);
  }

  private static RuleResult evaluateSchemaColumn(
      SchemaColumnRule rule, TableEvidence evidence, Instant evaluatedAt) {
    ColumnEvidence column = evidence.columns().get(rule.column());
    String expected = schemaExpectation(rule);
    if (column == null) {
      return result(
          rule,
          Status.FAIL,
          "column=missing",
          expected,
          ReasonCode.MISSING_COLUMN,
          evidence,
          evaluatedAt);
    }
    if (rule.dataType() != null && !rule.dataType().equals(column.dataType())) {
      return result(
          rule,
          Status.FAIL,
          schemaObservation(column),
          expected,
          ReasonCode.TYPE_MISMATCH,
          evidence,
          evaluatedAt);
    }
    if (rule.nullable() != null && rule.nullable() != !column.required()) {
      return result(
          rule, Status.FAIL, schemaObservation(column), expected, null, evidence, evaluatedAt);
    }
    return result(
        rule, Status.PASS, schemaObservation(column), expected, null, evidence, evaluatedAt);
  }

  private static RuleResult result(
      RuleSpec rule,
      Status status,
      String observed,
      String expected,
      ReasonCode reasonCode,
      TableEvidence evidence,
      Instant evaluatedAt) {
    return new RuleResult(
        rule.id(),
        rule.type(),
        status,
        observed,
        expected,
        reasonCode,
        evidence.snapshotId(),
        evaluatedAt);
  }

  private static String range(Long min, Long max) {
    if (min != null && max != null) {
      return min + "<=row_count<=" + max;
    }
    return min != null ? "row_count>=" + min : "row_count<=" + max;
  }

  private static String coverageObservation(ColumnEvidence column) {
    return "null_metrics="
        + column.coverage().nullCountFiles()
        + "/"
        + column.coverage().totalDataFiles();
  }

  private static String schemaExpectation(SchemaColumnRule rule) {
    StringBuilder value = new StringBuilder("column=present");
    if (rule.dataType() != null) {
      value.append(", type=").append(rule.dataType());
    }
    if (rule.nullable() != null) {
      value.append(", nullable=").append(rule.nullable());
    }
    return value.toString();
  }

  private static String schemaObservation(ColumnEvidence column) {
    return "type=" + column.dataType() + ", nullable=" + !column.required();
  }

  private static String decimal(double value) {
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }

  private static String compactDuration(Duration duration) {
    long seconds = duration.getSeconds();
    if (seconds % 86_400 == 0) {
      return seconds / 86_400 + "d";
    }
    if (seconds % 3_600 == 0) {
      return seconds / 3_600 + "h";
    }
    if (seconds % 60 == 0) {
      return seconds / 60 + "m";
    }
    return seconds + "s";
  }
}
