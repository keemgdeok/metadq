package io.github.keemgdeok.metadq.benchmark;

import io.github.keemgdeok.metadq.iceberg.EvidenceCollector;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.types.Types;

public final class MetadataBenchmark {
  private static final int[] DEFAULT_COUNTS = {1_000, 10_000, 100_000};
  private static final Schema SCHEMA = schema();
  private static final Map<Integer, Long> VALUES = metricValues(1);
  private static final Map<Integer, Long> NULLS = metricValues(0);

  private MetadataBenchmark() {}

  public static void main(String[] arguments) throws Exception {
    int[] counts = arguments.length == 0 ? DEFAULT_COUNTS : parseCounts(arguments);
    warmUp();
    System.out.println("files,mode,median_runtime_ms,peak_heap_mb,metadata_record_count");
    for (int count : counts) {
      run(count, "row_count", Set.of());
      run(count, "one_column", Set.of("column_1"));
      run(count, "all_columns", null);
    }
  }

  private static void run(int fileCount, String mode, Set<String> requestedColumns)
      throws Exception {
    try (Fixture fixture = createFixture(fileCount)) {
      long[] elapsedMillis = new long[3];
      long peakBytes = 0;
      long recordCount = 0;
      for (int run = 0; run < elapsedMillis.length; run++) {
        System.gc();
        resetHeapPeaks();
        long started = System.nanoTime();
        var evidence =
            requestedColumns == null
                ? new EvidenceCollector().collect("benchmark.events", fixture.table())
                : new EvidenceCollector()
                    .collect("benchmark.events", fixture.table(), requestedColumns);
        elapsedMillis[run] = (System.nanoTime() - started) / 1_000_000;
        peakBytes = Math.max(peakBytes, peakHeapBytes());
        recordCount = evidence.metadataRecordCount();
      }
      Arrays.sort(elapsedMillis);
      System.out.printf(
          "%d,%s,%d,%d,%d%n",
          fileCount, mode, elapsedMillis[1], peakBytes / (1024 * 1024), recordCount);
    }
  }

  private static void warmUp() throws Exception {
    try (Fixture fixture = createFixture(100)) {
      new EvidenceCollector().collect("benchmark.warmup", fixture.table());
    }
  }

  private static Fixture createFixture(int fileCount) {
    InMemoryCatalog catalog = new InMemoryCatalog();
    catalog.initialize("benchmark", Map.of("warehouse", "memory://" + UUID.randomUUID()));
    Namespace namespace = Namespace.of("benchmark");
    catalog.createNamespace(namespace);
    Table table =
        catalog
            .buildTable(TableIdentifier.of(namespace, "events"), SCHEMA)
            .withProperty(TableProperties.FORMAT_VERSION, "2")
            .create();
    AppendFiles append = table.newAppend();
    for (int index = 0; index < fileCount; index++) {
      append.appendFile(
          DataFiles.builder(PartitionSpec.unpartitioned())
              .withPath("memory://benchmark/data/file-" + index + ".parquet")
              .withFormat(FileFormat.PARQUET)
              .withFileSizeInBytes(1_024)
              .withRecordCount(1)
              .withMetrics(new Metrics(1L, null, VALUES, NULLS, null))
              .build());
    }
    append.commit();
    return new Fixture(table, catalog);
  }

  private static Schema schema() {
    Types.NestedField[] fields = new Types.NestedField[10];
    fields[0] = Types.NestedField.required(1, "event_id", Types.LongType.get());
    for (int index = 1; index < fields.length; index++) {
      fields[index] =
          Types.NestedField.optional(index + 1, "column_" + index, Types.LongType.get());
    }
    return new Schema(fields);
  }

  private static Map<Integer, Long> metricValues(long value) {
    Map<Integer, Long> metrics = new LinkedHashMap<>();
    for (Types.NestedField field : SCHEMA.columns()) {
      metrics.put(field.fieldId(), value);
    }
    return Map.copyOf(metrics);
  }

  private static int[] parseCounts(String[] arguments) {
    int[] counts = new int[arguments.length];
    for (int index = 0; index < arguments.length; index++) {
      counts[index] = Integer.parseInt(arguments[index]);
      if (counts[index] <= 0) {
        throw new IllegalArgumentException("File counts must be positive");
      }
    }
    return counts;
  }

  private static void resetHeapPeaks() {
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP) {
        pool.resetPeakUsage();
      }
    }
  }

  private static long peakHeapBytes() {
    long bytes = 0;
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP) {
        bytes += pool.getPeakUsage().getUsed();
      }
    }
    return bytes;
  }

  private record Fixture(Table table, InMemoryCatalog catalog) implements AutoCloseable {
    @Override
    public void close() throws IOException {
      catalog.close();
    }
  }
}
