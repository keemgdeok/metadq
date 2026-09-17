# Roadmap

`metadq` 0.1.0 includes the runnable demo, four rules, guarded metadata path,
cross-platform release checks, and an executable JAR.

## Current priorities

- Validate the demo with Iceberg users and record concrete tables or workflows
  where they would try it.
- Add cold-process REST/MinIO request and transfer measurements to the published
  1k, 10k, and 100k collector benchmark.

## Later, only from measured demand

- Partition-level checks.
- Snapshot-to-snapshot drift.
- JUnit or SARIF output.
- Direct metadata-location input.

Row scans, a server, a scheduler, a plugin framework, and automatic table
changes remain out of scope.
