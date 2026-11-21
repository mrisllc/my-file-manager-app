# MRIS Media Modularization Starter Kit

This kit starts with **analysis-only** automation. It deliberately does not split or rewrite Kotlin source because regex-based splitting can corrupt nested declarations, strings, annotations, and dependencies.

## Setup
1. Place the repository's current `MainActivity.kt` at `source/MainActivity.kt`.
2. Commit the kit and source.
3. Open GitHub Actions and run **Analyze Kotlin Source**.
4. Download the `mris-media-source-analysis` artifact and inspect `analysis-report.md`, `declarations.csv`, and `source-analysis.json`.

## Local run
```bash
python modularization/scripts/analyze_source.py --source source/MainActivity.kt --out modularization/reports
```

## Next phase
Use the analysis inventory to prepare a reviewed declaration-to-module map. The actual extraction phase should use Kotlin PSI/compiler parser and run a Gradle build against the complete Android project. This starter does not claim to produce a compilable multi-module project yet.
