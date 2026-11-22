# Modularization Phase 1: extraction blueprint

## Current repository state

This repository is currently a Kotlin modularization starter kit, not a complete Android Gradle application. The root contains the large `MainActivity.kt` source and analysis/extraction tooling, but it does not contain the Android app's `settings.gradle(.kts)`, root Android Gradle build file, app module, manifest, resources, or theme source required to compile the application. The standalone `extractor/` Gradle project is a JVM tool and is not the Android application build.

Because of that, moving declarations into Android modules now would create unbuildable source and could break Room, Compose, resource, and package references. This phase deliberately leaves the production Kotlin source untouched until the Android project needed to validate changes is available.

## Proposed module boundaries

```text
:app
:core:model
:core:ui
:core:localization
:core:database
:core:filesystem
:data:bookmarks
:data:media
:feature:files
:feature:gallery
:feature:video
:feature:audio
:feature:document
:feature:pdf
:feature:archive
:feature:trash
:feature:settings
:feature:tools
:feature:home
```

### Dependency direction

- `:app` is the composition root. It owns `MainActivity`, app navigation, and dependency wiring.
- `:feature:*` can depend on `:core:*` and data APIs, but feature modules must not depend on other feature implementations.
- `:data:*` depends on `:core:model` and the database/filesystem abstractions it needs.
- `:core:*` must not depend on feature modules.
- Keep Android UI types such as `ImageVector` out of `:core:model`; place UI-specific category metadata in `:core:ui` or the consuming feature.

## Safe extraction order

1. Bring the actual Android Gradle project, manifest, resources, theme, and dependency versions into this repository. Confirm the current app builds before changing source.
2. Reconcile the package names: the source currently declares `com.example`, while `modularization/config/module-mapping.json` proposes `com.mris.media` as the destination root. Choose and document a migration strategy before moving files.
3. Extract platform-independent models and localization helpers, then compile and test.
4. Extract Room entities, DAOs, database, and repositories without changing the existing database name, schema, version, or migration behavior.
5. Extract filesystem/domain operations behind feature-facing APIs; keep Android permission handling in the app/UI layer.
6. Move one feature at a time (starting with a small, isolated screen), fixing visibility and dependencies and running the Android build after each move.
7. Reduce `MainActivity` to activity setup, app theme, and the app entry composable only after all feature entry points are wired and validated.

## Required validation gates

- Kotlin PSI inventory has no unexplained unmapped or duplicate declarations.
- Every moved declaration has the correct package, imports, visibility, and module dependencies.
- Compose previews/UI compile with the actual theme and resources.
- Room schema and migrations remain compatible with existing user data.
- Run the repository's Android debug build and available unit/instrumentation tests after each extraction batch.
- Do not use regex or line-range slicing to move Kotlin declarations.

## Phase 1 outcome

This document records the architecture and safety gates. It does not claim that `MainActivity.kt` has been split. Source extraction is blocked until the complete Android project is available so each change can be checked against a real build.
