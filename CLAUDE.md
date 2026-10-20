# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Chimahon Lite is a local-first Android manga reader for language learning (OCR, Hoshidicts dictionary lookup, Anki mining). It is a fork of Chimahon, which itself descends from Komikku → TachiyomiSY → Mihon/Tachiyomi (plus Aniyomi-derived code). Much of the inherited code (anime, novels, online extensions, downloads, Updates tab, E-Hentai/`exh`) is still in the tree but **removed from the active product** — see README.md for the kept/removed lists. Don't extend or "fix" dormant anime/novel/extension surfaces unless asked.

Product model: the filesystem is the source of truth. The Browse tab opens the local source directly; the user picks a manga folder via Android's document-tree (SAF) access, and each immediate subfolder is one manga. On app launch and library pull-to-refresh, `SyncLocalLibrary` adds new folders to the library and drops removed ones (keeping DB rows and history, and restoring them if the folder returns); manga the user removed stay removed. The app-owned `local` directory is the fallback when no folder is selected. Manga "migration" has been replaced by folder relinking (`RelinkFolderScreen`, `domain/manga/interactor/Relink*`).

## Commands

Use the JDK bundled with Android Studio. On Windows use `gradlew.bat` / `./gradlew` from Git Bash.

```bash
./gradlew assembleDebug                  # debug APK (applicationId app.chimahon.lite.dev)
./gradlew assemblePreview                # what CI builds for PRs
./gradlew assembleRelease -Penable-updater
./gradlew spotlessCheck                  # lint (ktlint via Spotless) — CI gate
./gradlew spotlessApply                  # auto-format
./gradlew testReleaseUnitTest            # unit tests as run in CI
./gradlew :app:testDebugUnitTest --tests "eu.kanade.domain.manga.interactor.RelinkLocalMangaTest"
./gradlew :chimahon:testDebugUnitTest    # dictionary/OCR/Anki module tests
```

Gradle properties: `-Penable-updater`, `-Pdisable-code-shrink`, `-Pinclude-dependency-info`, `-PreleaseVersionName=… -PreleaseVersionCode=…` (see `buildSrc/src/main/kotlin/mihon/buildlogic/BuildConfig.kt`). Build types: `debug`, `release`, `releaseTest`, `foss`, `preview`, `benchmark`.

Per README, changes are typically verified by static inspection and compilation only (no device/emulator run). Tests use JUnit 5.

The `chimahon` module needs the `chimahon/src/main/cpp/hoshidicts` git submodule (`git submodule update --init`) and NDK 27. If native builds get stale, delete `app/.cxx` and `chimahon/.cxx`.

## Module layout

- `app` — Android app; namespace `eu.kanade.tachiyomi`. Packages: `eu.kanade.*` (UI/presentation/data from Tachiyomi), `mihon.*` (newer Mihon code incl. `mihon/core/migration`), `exh.*` (SY-inherited, mostly dormant).
- `chimahon` — Chimahon-specific features (namespace `mihon.chimahon`, package `chimahon.*`): dictionary (`HoshiDicts`, JNI to C++ hoshidicts + bundled sqlite), `ocr`, `anki`, `audio`, reader widgets. Has its own CMake build.
- `chimahon-local-ocr` — optional native on-device OCR module. Included only if `chimahon-local-ocr/build.gradle.kts` exists; the app exposes this as `BuildConfig.HAS_LOCAL_OCR`, so code must tolerate its absence.
- `source-local` — the local filesystem source (`tachiyomi.source.local.LocalSource`, KMP-style `commonMain`/`androidMain`).
- `domain`, `data` — clean-architecture layers (`tachiyomi.domain.*` interactors/repositories; `data` implements them with SQLDelight — schemas in `data/src/main/sqldelight` and `data/src/main/sqldelightanime`).
- `source-api`, `core:common`, `core:archive`, `core-metadata`, `presentation-core`, `presentation-widget` — shared infrastructure inherited from Mihon.
- `i18n`, `i18n-sy`, `i18n-kmk`, `i18n-ank` — Moko resources string bundles per upstream lineage; put new strings in the bundle matching the code's origin.
- `buildSrc` — convention plugins (`mihon.android.application`, `mihon.library`, `mihon.code.lint`, …). Version catalogs: `gradle/libs.versions.toml`, plus `kotlinx`, `androidx`, `compose`, `sylibs` catalogs.

## Conventions

- Always commit your changes when a task is done, unless told not to.
- **Fork markers**: changes to upstream-derived files are fenced with comments like `// KMK -->` / `// KMK <--`, `// SY -->` / `// SY <--`, and `// Chimahon -->` / `// Chimahon <--`. Wrap new Chimahon-specific edits inside existing upstream files the same way to keep future merges tractable.
- DI uses Injekt (`Injekt.get()`, `injectLazy()`); UI is Jetpack Compose with Voyager navigation.
- Preference/data migrations go in `app/src/main/java/mihon/core/migration/migrations/` and must be registered in `Migrations.kt`.
- Kotlin style is enforced by `.editorconfig` (ktlint `intellij_idea` style, 4-space indent, 120 cols, trailing commas allowed, no star imports).
