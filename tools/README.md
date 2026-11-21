# Phase 3: PSI inventory + extraction-plan generator

This stage uses Kotlin PSI (not regex) to parse declarations and produce a conservative extraction plan. It does **not** move source code or edit the original file.

## Expected input
`source/MainActivity.kt` and `config/module-mapping.json`.

## Output
- `reports/psi-inventory.json`: top-level PSI declarations with kind, name, line range and mapped module.
- `reports/extraction-plan.json`: mapped/unmapped declarations and duplicate mapping warnings.
- `reports/phase3-summary.md`.

The tool intentionally stops before source extraction. A real extractor must preserve imports, package visibility, nested declarations, overloads, references, Compose state, and Android resources; the generated plan is a review artifact.
