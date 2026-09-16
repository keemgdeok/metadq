package io.github.keemgdeok.metadq.iceberg;

import io.github.keemgdeok.metadq.core.TableEvidence;
import io.github.keemgdeok.metadq.core.TableEvidence.ColumnEvidence;
import io.github.keemgdeok.metadq.core.TableEvidence.MetricCoverage;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

public final class EvidenceCollector {
  public TableEvidence collect(String tableName, Table table) throws IOException {
    Set<String> requestedColumns = new HashSet<>();
    for (Types.NestedField field : table.schema().columns()) {
      if (field.type().isPrimitiveType()) {
        requestedColumns.add(field.name());
      }
    }
    return collect(tableName, table, requestedColumns);
  }

  public TableEvidence collect(String tableName, Table table, Set<String> requestedColumns)
      throws IOException {
    int formatVersion = parseFormatVersion(table);
    if (formatVersion < 1 || formatVersion > 2) {
      throw new UnsupportedFormatVersionException(formatVersion);
    }

    Snapshot snapshot = table.currentSnapshot();
    Map<String, ColumnAccumulator> accumulators = new LinkedHashMap<>();
    List<String> primitiveColumns = new ArrayList<>();
    for (Types.NestedField field : table.schema().columns()) {
      boolean primitive = field.type().isPrimitiveType();
      boolean requested = primitive && requestedColumns.contains(field.name());
      if (requested) {
        primitiveColumns.add(field.name());
      }
      accumulators.put(field.name(), new ColumnAccumulator(field, primitive, requested));
    }

    long dataFileCount = 0;
    long referencedBytes = 0;
    long recordCount = 0;
    boolean recordCountsInconsistent = false;
    boolean referencedDataBytesInconsistent = false;
    Map<String, Long> deleteCounts = new TreeMap<>();
    Set<DeleteKey> seenDeletes = new HashSet<>();

    if (snapshot != null) {
      TableScan scan = table.newScan();
      if (!primitiveColumns.isEmpty()) {
        scan = scan.includeColumnStats(primitiveColumns);
      }
      try (CloseableIterable<FileScanTask> tasks = scan.planFiles()) {
        for (FileScanTask task : tasks) {
          DataFile file = task.file();
          dataFileCount++;
          if (file.fileSizeInBytes() < 0) {
            referencedDataBytesInconsistent = true;
          }
          if (file.recordCount() < 0) {
            recordCountsInconsistent = true;
          }
          try {
            referencedBytes = Math.addExact(referencedBytes, file.fileSizeInBytes());
          } catch (ArithmeticException exception) {
            referencedBytes = file.fileSizeInBytes() >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
            referencedDataBytesInconsistent = true;
          }
          try {
            recordCount = Math.addExact(recordCount, file.recordCount());
          } catch (ArithmeticException exception) {
            recordCount = file.recordCount() >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
            recordCountsInconsistent = true;
          }
          for (ColumnAccumulator accumulator : accumulators.values()) {
            accumulator.accept(file);
          }
          for (DeleteFile delete : task.deletes()) {
            DeleteKey key = new DeleteKey(delete.content().lowerCaseName(), delete.location());
            if (seenDeletes.add(key)) {
              deleteCounts.merge(key.contentType(), 1L, Long::sum);
            }
          }
        }
      }
    }

    Map<String, ColumnEvidence> columns = new LinkedHashMap<>();
    for (ColumnAccumulator accumulator : accumulators.values()) {
      ColumnEvidence column = accumulator.toEvidence(dataFileCount);
      columns.put(column.name(), column);
    }

    return new TableEvidence(
        tableName,
        formatVersion,
        snapshot == null ? null : snapshot.snapshotId(),
        snapshot == null ? null : Instant.ofEpochMilli(snapshot.timestampMillis()),
        snapshot == null ? null : snapshot.operation(),
        dataFileCount,
        referencedBytes,
        recordCount,
        deleteCounts,
        columns,
        recordCountsInconsistent,
        referencedDataBytesInconsistent);
  }

  private static int parseFormatVersion(Table table) {
    if (table instanceof HasTableOperations withOperations) {
      return withOperations.operations().current().formatVersion();
    }
    String raw = table.properties().getOrDefault(TableProperties.FORMAT_VERSION, "1");
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          "Invalid Iceberg format-version property: " + raw, exception);
    }
  }

  private record DeleteKey(String contentType, String path) {}

  private static final class ColumnAccumulator {
    private final Types.NestedField field;
    private final boolean primitive;
    private final boolean collectMetrics;
    private final boolean nanApplicable;
    private long valueCountFiles;
    private long nullCountFiles;
    private long nanCountFiles;
    private long boundsFiles;
    private long nullCount;
    private boolean nullCountsCompleteForRows = true;
    private boolean inconsistent;

    private ColumnAccumulator(Types.NestedField field, boolean primitive, boolean collectMetrics) {
      this.field = field;
      this.primitive = primitive;
      this.collectMetrics = collectMetrics;
      Type.TypeID typeId = field.type().typeId();
      this.nanApplicable = typeId == Type.TypeID.FLOAT || typeId == Type.TypeID.DOUBLE;
    }

    private void accept(DataFile file) {
      if (!collectMetrics) {
        return;
      }

      Long values = value(file.valueCounts(), field.fieldId());
      Long nulls = value(file.nullValueCounts(), field.fieldId());
      Long nans = value(file.nanValueCounts(), field.fieldId());
      if (values != null) {
        valueCountFiles++;
      }
      if (nulls != null) {
        nullCountFiles++;
        try {
          nullCount = Math.addExact(nullCount, nulls);
        } catch (ArithmeticException exception) {
          nullCount = nulls >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
          inconsistent = true;
        }
      } else if (file.recordCount() > 0) {
        nullCountsCompleteForRows = false;
      }
      if (nans != null) {
        nanCountFiles++;
      }
      if (contains(file.lowerBounds(), field.fieldId())
          && contains(file.upperBounds(), field.fieldId())) {
        boundsFiles++;
      }

      if (isNegative(values) || isNegative(nulls) || isNegative(nans)) {
        inconsistent = true;
      }
      if (values != null && nulls != null && nulls > values) {
        inconsistent = true;
      }
      if (field.isRequired() && nulls != null && nulls > 0) {
        inconsistent = true;
      }
      long knownNulls = nulls == null ? 0 : nulls;
      if (values != null && nans != null && nans > values - knownNulls) {
        inconsistent = true;
      }
    }

    private ColumnEvidence toEvidence(long totalDataFiles) {
      return new ColumnEvidence(
          field.fieldId(),
          field.name(),
          field.type().toString(),
          primitive,
          field.isRequired(),
          nanApplicable,
          new MetricCoverage(
              totalDataFiles, valueCountFiles, nullCountFiles, nanCountFiles, boundsFiles),
          nullCount,
          nullCountsCompleteForRows,
          inconsistent);
    }

    private static boolean isNegative(Long value) {
      return value != null && value < 0;
    }

    private static <T> boolean contains(Map<Integer, T> values, int fieldId) {
      return values != null && values.containsKey(fieldId);
    }

    private static Long value(Map<Integer, Long> values, int fieldId) {
      return values == null ? null : values.get(fieldId);
    }
  }
}
