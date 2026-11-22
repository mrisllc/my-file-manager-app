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

## Current limitation
This repository does not yet include the Android app's Gradle project, manifest, resources, or theme. The separate `extractor/` project is a JVM analysis utility, not the Android app. Consequently, source declarations cannot yet be moved into Android modules and build-validated here. See [the Phase 1 extraction blueprint](modularization/PHASE-1.md) for the module boundaries, extraction order, and validation gates. The production Kotlin source remains unchanged.

## Next phase
Bring in the complete Android Gradle project and confirm its baseline build. Then use the PSI inventory to prepare and review a declaration-to-module map. The extraction phase must preserve imports, package visibility, nested declarations, overloads, references, Compose state, Room schema/migrations, and Android resources; run the Android build and tests after each extraction batch.
