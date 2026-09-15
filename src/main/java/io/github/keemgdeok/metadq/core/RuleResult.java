package io.github.keemgdeok.metadq.core;

import java.time.Instant;

public record RuleResult(
    String ruleId,
    String ruleType,
    Status status,
    String observed,
    String expected,
    ReasonCode reasonCode,
    Long snapshotId,
    Instant evaluatedAt) {}
