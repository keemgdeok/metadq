# Contributing to metadq

Thanks for helping make Iceberg metadata checks safer and easier to understand.

## Before opening a change

- Open an issue for a new rule or a change to evidence semantics.
- Keep v0.1 read-only and metadata-only; no row-scan fallback.
- Include a regression test for every correctness change.
- Never interpret a missing metric as zero.

## Local checks

Java 17 or newer is required.

```console
./gradlew test
./gradlew shadowJar
java -jar build/libs/metadq.jar doctor --demo
```

Keep changes focused. A new abstraction should solve a demonstrated second use
case, not a hypothetical future one.
