package io.github.keemgdeok.metadq.core;

import java.time.Duration;

public sealed interface RuleSpec
    permits RuleSpec.RowCountRule,
        RuleSpec.NullRatioRule,
        RuleSpec.LastCommitAgeRule,
        RuleSpec.SchemaColumnRule {

  String id();

  String type();

  record RowCountRule(String id, Long min, Long max) implements RuleSpec {
    @Override
    public String type() {
      return "table.row_count";
    }
  }

  record NullRatioRule(String id, String column, double max) implements RuleSpec {
    @Override
    public String type() {
      return "column.null_ratio";
    }
  }

  record LastCommitAgeRule(String id, Duration max) implements RuleSpec {
    @Override
    public String type() {
      return "table.last_commit_age";
    }
  }

  record SchemaColumnRule(String id, String column, String dataType, Boolean nullable)
      implements RuleSpec {
    @Override
    public String type() {
      return "schema.column";
    }
  }
}
