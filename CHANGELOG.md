# Changelog

## 2026-09-17

### Features and Fixes

- Bumped the `confluent-flink-table-api-java-plugin` to `2.3-6`.
- Extended `Example_07_Changelogs` with `toChangelog`/`fromChangelog` examples: default conversion,
  op-column rename, round-trip, `error_handling`, `produces_full_deletes`, and
  `partitionBy().process()`.
- Added the `advanced/changelogs` package with two examples: `Example_00_CdcIngestion` and
  `Example_01_SoftDeleteExport`.
- Added `Example_12_ManagingIndependentArtifacts`, showing how to manage artifacts independently from
  statement submission.
- Refactored the `Example_10_ProcessTableFunction` test harness to use the new table-argument builder.

## 2026-07-16

### Features and Fixes

- The Table API for Java on Confluent Cloud is now generally available. Bumped the
  `confluent-flink-table-api-java-plugin` to `2.3-3` and updated the documentation for the GA
  surface.

## 2026-07-01

### Features and Fixes

- Added `log4j2.properties` with default logging configuration.

## 2026-04-15

### Features and Fixes

- Added Example_11: ProcessTableFunction with event-time timers, demonstrating user inactivity
  detection on the `examples.marketplace.clicks` table.

## 2024-09-13

### Features and Fixes

- Initial release.