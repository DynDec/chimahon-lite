Native dictionary storage regressions

Requires CMake, Python and a C++23 compiler (GCC 14+ or a suitable Clang/libc++).
The project's Android NDK can also build these targets; run them on an Android
device with freshly generated fixtures when cross compiling.

From the repository root, using PowerShell and a configured native toolchain:

  New-Item -ItemType Directory -Force chimahon/build/dictionary-storage
  git -C chimahon/src/main/cpp/hoshidicts show 156f586:src/importer.cpp |
      Set-Content -Encoding utf8 chimahon/build/dictionary-storage/legacy_importer.cpp
  cmake -S chimahon/src/test/cpp/dictionary-storage -B chimahon/build/dictionary-storage/native `
      -DLEGACY_IMPORTER_SOURCE:FILEPATH="<absolute forward-slash path>/chimahon/build/dictionary-storage/legacy_importer.cpp"
  cmake --build chimahon/build/dictionary-storage/native --target storage_tests legacy_import
  python chimahon/src/test/cpp/dictionary-storage/run_tests.py --native-dir chimahon/build/dictionary-storage/native

LEGACY_IMPORTER_SOURCE must come from the original pinned revision, not the
modified working tree. It produces genuine v3 fixtures for compatibility and
size comparisons. On Windows, put the compiler's runtime DLLs on PATH.

Coverage: all 4,000 generated definitions in v3/v4, low-memory/normal import,
deinflected lookup, scores, frequencies, pitch, kanji, styles, media, concurrent
queries across mixed formats, missing/empty/corrupt compression dictionaries,
small-corpus fallback, metadata-only dictionaries, and replacement imports in
both directions. Reported storage savings describe this synthetic fixture only.

The parent project pins a hoshidicts storage backport commit. The backport
preserves v1-v3 readers and the Kotlin/JNI interface.
New v4 imports use a trained dict.zstd file, copied/backed up with the other
dictionary files. Existing imports are not converted automatically.
