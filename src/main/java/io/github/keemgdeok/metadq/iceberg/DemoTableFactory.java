package io.github.keemgdeok.metadq.iceberg;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.DataFile;
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
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Types;

public final class DemoTableFactory {
  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.required(1, "event_id", Types.StringType.get()),
          Types.NestedField.optional(2, "user_id", Types.LongType.get()),
          Types.NestedField.optional(3, "score", Types.DoubleType.get()));

  private DemoTableFactory() {}

  public static DemoTable create() {
    return create(2, true);
  }

  static DemoTable create(int formatVersion) {
    return create(formatVersion, true);
  }

  static DemoTable createEmpty() {
    return create(2, false);
  }

  static DemoTable createEmpty(int formatVersion) {
    return create(formatVersion, false);
  }

  private static DemoTable create(int formatVersion, boolean appendFiles) {
    InMemoryCatalog catalog = new InMemoryCatalog();
    catalog.initialize("demo", Map.of("warehouse", "memory://metadq/" + UUID.randomUUID()));
    Namespace namespace = Namespace.of("demo");
    catalog.createNamespace(namespace);
    TableIdentifier identifier = TableIdentifier.of(namespace, "events");
    Table table =
        catalog
            .buildTable(identifier, SCHEMA)
            .withProperty(TableProperties.FORMAT_VERSION, Integer.toString(formatVersion))
            .create();

    List<String> contentPaths = new ArrayList<>();
    if (appendFiles) {
      for (int index = 0; index < 3; index++) {
        String path = "memory://metadq/content/events-" + index + ".parquet";
        contentPaths.add(path);
        table.newAppend().appendFile(dataFile(path, index)).commit();
      }
    }
    return new DemoTable(table, List.copyOf(contentPaths), catalog);
  }

  private static DataFile dataFile(String path, int index) {
    long rows = 400;
    Map<Integer, Long> values = Map.of(1, rows, 2, rows, 3, rows);
    Map<Integer, Long> nulls = new LinkedHashMap<>();
    nulls.put(1, 0L);
    if (index < 2) {
      nulls.put(2, index == 0 ? 0L : 5L);
    }
    nulls.put(3, index == 0 ? 2L : index == 1 ? 0L : 1L);
    Map<Integer, Long> nans = Map.of(3, 0L);

    Map<Integer, ByteBuffer> lower = new LinkedHashMap<>();
    Map<Integer, ByteBuffer> upper = new LinkedHashMap<>();
    lower.put(1, Conversions.toByteBuffer(Types.StringType.get(), "event-000"));
    upper.put(1, Conversions.toByteBuffer(Types.StringType.get(), "event-999"));
    lower.put(2, Conversions.toByteBuffer(Types.LongType.get(), 1L));
    upper.put(2, Conversions.toByteBuffer(Types.LongType.get(), 999L));
    lower.put(3, Conversions.toByteBuffer(Types.DoubleType.get(), 0.0));
    upper.put(3, Conversions.toByteBuffer(Types.DoubleType.get(), 100.0));

    Metrics metrics = new Metrics(rows, null, values, nulls, nans, lower, upper);
    return DataFiles.builder(PartitionSpec.unpartitioned())
        .withPath(path)
        .withFormat(FileFormat.PARQUET)
        .withFileSizeInBytes(1_024L * (index + 1))
        .withRecordCount(rows)
        .withMetrics(metrics)
        .build();
  }

  public record DemoTable(Table table, List<String> contentPaths, InMemoryCatalog catalog)
      implements AutoCloseable {
    @Override
    public void close() throws IOException {
      catalog.close();
    }
  }
}
