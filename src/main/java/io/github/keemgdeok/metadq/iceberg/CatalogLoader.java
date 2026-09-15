package io.github.keemgdeok.metadq.iceberg;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;

public final class CatalogLoader {
  private CatalogLoader() {}

  public static CatalogTable load(Path propertiesPath, String tableIdentifier) throws IOException {
    if (!Files.isRegularFile(propertiesPath)) {
      throw new IllegalArgumentException("Catalog properties file not found: " + propertiesPath);
    }

    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(propertiesPath)) {
      properties.load(input);
    }
    Map<String, String> options = new LinkedHashMap<>();
    for (String name : properties.stringPropertyNames()) {
      options.put(name, properties.getProperty(name));
    }

    String configuredType = options.get(CatalogUtil.ICEBERG_CATALOG_TYPE);
    if (configuredType != null && !"rest".equalsIgnoreCase(configuredType)) {
      throw new IllegalArgumentException(
          "Only REST catalogs are supported in v0.1; found type=" + configuredType);
    }
    options.put(CatalogUtil.ICEBERG_CATALOG_TYPE, "rest");
    String catalogName = options.getOrDefault("name", "metadq");

    Catalog catalog = CatalogUtil.buildIcebergCatalog(catalogName, options, null);
    try {
      Table table = catalog.loadTable(TableIdentifier.parse(tableIdentifier));
      return new CatalogTable(table, catalog);
    } catch (RuntimeException exception) {
      closeQuietly(catalog);
      throw exception;
    }
  }

  private static void closeQuietly(Catalog catalog) {
    if (catalog instanceof Closeable closeable) {
      try {
        closeable.close();
      } catch (Exception ignored) {
      }
    }
  }

  public record CatalogTable(Table table, Catalog catalog) implements Closeable {
    @Override
    public void close() throws IOException {
      if (catalog instanceof Closeable closeable) {
        closeable.close();
      }
    }
  }
}
