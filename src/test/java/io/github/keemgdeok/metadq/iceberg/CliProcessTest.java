package io.github.keemgdeok.metadq.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.keemgdeok.metadq.cli.Metadq;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.apache.iceberg.rest.responses.ConfigResponseParser;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.rest.responses.LoadTableResponseParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CliProcessTest {
  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

  @TempDir Path directory;

  @Test
  void packagedCheckUsesDocumentedExitCodes() throws Exception {
    try (TestRestCatalog catalog = TestRestCatalog.start()) {
      Path properties = catalog.writeProperties(directory);

      ProcessResult pass = runCheck(properties, rules("table.row_count", "max: 0"));
      assertEquals(0, pass.exitCode(), pass.output());
      ProcessResult fail = runCheck(properties, rules("table.row_count", "min: 1"));
      assertEquals(1, fail.exitCode(), fail.output());
      ProcessResult unknown = runCheck(properties, rules("table.last_commit_age", "max: 1h"));
      assertEquals(3, unknown.exitCode(), unknown.output());

      ProcessResult error =
          runJar(
              "check",
              "--catalog-properties",
              properties.toString(),
              "--table",
              "analytics.events",
              "--rules",
              directory.resolve("missing.yml").toString());
      assertEquals(2, error.exitCode());
      assertTrue(error.output().contains("Rules file not found"));
    }
  }

  @Test
  void doctorAndAllRulesRunBehindMissingContentFileGuard() throws Exception {
    try (DemoTableFactory.DemoTable demo = DemoTableFactory.create();
        TestRestCatalog catalog = TestRestCatalog.start(metadata(demo))) {
      Path properties = catalog.writeProperties(directory);
      InMemoryFileIO io = (InMemoryFileIO) demo.table().io();
      for (String path : demo.contentPaths()) {
        assertFalse(io.fileExists(path));
      }

      ProcessResult doctor =
          execute(
              "doctor",
              "--catalog-properties",
              properties.toString(),
              "--table",
              "analytics.events");
      assertEquals(0, doctor.exitCode(), doctor.output());
      assertTrue(doctor.output().contains("No content data files opened."));

      Path allRules =
          writeRules(
              """
              version: 1
              rules:
                - id: rows
                  type: table.row_count
                  min: 1
                - id: nulls
                  type: column.null_ratio
                  column: user_id
                  max: 0
                - id: recent
                  type: table.last_commit_age
                  max: 1h
                - id: schema
                  type: schema.column
                  column: event_id
                  data_type: string
                  nullable: false
              """);
      ProcessResult check =
          execute(
              "check",
              "--catalog-properties",
              properties.toString(),
              "--table",
              "analytics.events",
              "--rules",
              allRules.toString());

      assertEquals(3, check.exitCode(), check.output());
      assertTrue(check.output().contains("rows"));
      assertTrue(check.output().contains("nulls"));
      assertTrue(check.output().contains("recent"));
      assertTrue(check.output().contains("schema"));
      assertEquals(2, catalog.tableLoads());
      for (String path : demo.contentPaths()) {
        assertFalse(io.fileExists(path));
      }
    }
  }

  private ProcessResult runCheck(Path properties, Path ruleFile) throws Exception {
    return runJar(
        "check",
        "--catalog-properties",
        properties.toString(),
        "--table",
        "analytics.events",
        "--rules",
        ruleFile.toString());
  }

  private Path rules(String type, String assertion) throws IOException {
    return writeRules(
        """
        version: 1
        rules:
          - id: result
            type: %s
            %s
        """
            .formatted(type, assertion));
  }

  private Path writeRules(String content) throws IOException {
    return Files.writeString(directory.resolve("rules-" + System.nanoTime() + ".yml"), content);
  }

  private static ProcessResult runJar(String... arguments) throws Exception {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    Path jar = Path.of("build", "libs", "metadq.jar").toAbsolutePath();
    String[] command = new String[arguments.length + 3];
    command[0] = java.toString();
    command[1] = "-jar";
    command[2] = jar.toString();
    System.arraycopy(arguments, 0, command, 3, arguments.length);

    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    boolean finished = process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      throw new AssertionError("Packaged CLI did not finish within " + PROCESS_TIMEOUT);
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new ProcessResult(process.exitValue(), output);
  }

  private static ProcessResult execute(String... arguments) {
    CommandLine commandLine = Metadq.createCommandLine();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    commandLine.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
    commandLine.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
    int exitCode = commandLine.execute(arguments);
    return new ProcessResult(
        exitCode, out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8));
  }

  private static TableMetadata metadata(DemoTableFactory.DemoTable demo) {
    return ((HasTableOperations) demo.table()).operations().current();
  }

  private record ProcessResult(int exitCode, String output) {}

  private static final class TestRestCatalog implements AutoCloseable {
    private final HttpServer server;
    private final String configJson;
    private final String tableJson;
    private int tableLoads;

    private TestRestCatalog(HttpServer server, String tableJson) {
      this.server = server;
      this.configJson = ConfigResponseParser.toJson(ConfigResponse.builder().build());
      this.tableJson = tableJson;
    }

    private static TestRestCatalog start() throws IOException {
      try (DemoTableFactory.DemoTable demo = DemoTableFactory.createEmpty()) {
        return start(metadata(demo));
      }
    }

    private static TestRestCatalog start(TableMetadata metadata) throws IOException {
      String tableJson =
          LoadTableResponseParser.toJson(
              LoadTableResponse.builder().withTableMetadata(metadata).build());
      HttpServer server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      TestRestCatalog catalog = new TestRestCatalog(server, tableJson);
      server.createContext("/", catalog::handle);
      server.start();
      return catalog;
    }

    private Path writeProperties(Path directory) throws IOException {
      String uri = "http://127.0.0.1:" + server.getAddress().getPort();
      return Files.writeString(
          directory.resolve("catalog.properties"),
          "uri=" + uri + "\nwarehouse=test\nio-impl=org.apache.iceberg.inmemory.InMemoryFileIO\n");
    }

    private synchronized int tableLoads() {
      return tableLoads;
    }

    private void handle(HttpExchange exchange) throws IOException {
      String path = exchange.getRequestURI().getPath();
      if (path.equals("/v1/config")) {
        respond(exchange, 200, configJson);
      } else if (path.equals("/v1/namespaces/analytics/tables/events")) {
        synchronized (this) {
          tableLoads++;
        }
        respond(exchange, 200, tableJson);
      } else {
        respond(
            exchange,
            404,
            "{\"error\":{\"message\":\"not found\",\"type\":\"NotFoundException\",\"code\":404}}");
      }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (var response = exchange.getResponseBody()) {
        response.write(bytes);
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
