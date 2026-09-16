package io.github.keemgdeok.metadq.core;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record TableEvidence(
    String tableName,
    int formatVersion,
    Long snapshotId,
    Instant snapshotTimestamp,
    String snapshotOperation,
    long dataFileCount,
    long referencedDataBytes,
    long metadataRecordCount,
    Map<String, Long> deleteFileCounts,
    Map<String, ColumnEvidence> columns,
    boolean recordCountsInconsistent,
    boolean referencedDataBytesInconsistent) {

  public TableEvidence {
    deleteFileCounts = immutableCopy(deleteFileCounts);
    columns = immutableCopy(columns);
  }

  private static <K, V> Map<K, V> immutableCopy(Map<K, V> source) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }

  public boolean hasCurrentSnapshot() {
    return snapshotId != null;
  }

  public boolean hasApplicableDeletes() {
    return deleteFileCounts.values().stream().anyMatch(count -> count > 0);
  }

  public record ColumnEvidence(
      int fieldId,
      String name,
      String dataType,
      boolean primitive,
      boolean required,
      boolean nanApplicable,
      MetricCoverage coverage,
      long nullCount,
      boolean nullCountsCompleteForRows,
      boolean inconsistentMetrics) {}

  public record MetricCoverage(
      long totalDataFiles,
      long valueCountFiles,
      long nullCountFiles,
      long nanCountFiles,
      long boundsFiles) {}
}
