package io.github.keemgdeok.metadq.iceberg;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogLoaderTest {
  @TempDir Path directory;

  @Test
  void rejectsMissingPropertiesFileBeforeCatalogCreation() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> CatalogLoader.load(directory.resolve("missing.properties"), "analytics.events"));

    assertTrue(error.getMessage().contains("Catalog properties file not found"));
  }

  @Test
  void rejectsNonRestCatalogType() throws Exception {
    Path properties = directory.resolve("catalog.properties");
    Files.writeString(properties, "type=hadoop\nwarehouse=/tmp/warehouse\n");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> CatalogLoader.load(properties, "analytics.events"));

    assertTrue(error.getMessage().contains("Only REST catalogs are supported"));
  }
}
