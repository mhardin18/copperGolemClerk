# Tools changelog

Changes to the developer tools in `tools/`: the in-game tests (`itest`), the performance
tests (`perf`) and the sorting board scripts (`board`). These tools aren't part of the plugin
jar. Plugin changes are in the main [CHANGELOG](../CHANGELOG.md).

Versions are the plugin release each change came out with.

## 3.1.0 (2026-11-05)

### New
- New tool `tools/perf` (see its [README](perf/README.md)). It measures how much server time
  golems cost, with the plugin off and at each `search-distance`, and compares versions. It
  changes nothing in the plugin.
