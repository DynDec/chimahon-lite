#include "hoshidicts.h"

#include <filesystem>
#include <fstream>
#include <future>
#include <iostream>
#include <stdexcept>

namespace fs = std::filesystem;
static constexpr int term_count = 4000;

void require(bool condition, const std::string& message) {
  if (!condition) throw std::runtime_error(message);
}

void import_zip(const fs::path& zip, const fs::path& output, bool low_ram) {
  const auto result = dictionary_importer::import(zip.string(), output.string(), low_ram);
  require(result.success, "import failed: " + (result.errors.empty() ? zip.string() : result.errors.front()));
  require(result.kanji_count == 1 && result.freq_count == 1 && result.pitch_count == 1 && result.media_count == 1,
          "import metadata counts changed");
}

#ifndef LEGACY_IMPORT_ONLY
std::string expression(int i) { return i == 0 ? "食べる" : "term" + std::to_string(i); }
std::string glossary(int i, bool short_glossaries = false) {
  if (short_glossaries) return "[\"" + std::to_string(i) + "\"]";
  return "[\"Definition number " + std::to_string(i) +
      ": This is a dictionary definition with shared vocabulary, grammar and example sentences.\"]";
}

DictionaryQuery load(const fs::path& path) {
  DictionaryQuery query;
  query.add_term_dict(path.string());
  query.add_freq_dict(path.string());
  query.add_pitch_dict(path.string());
  query.add_kanji_dict(path.string());
  return query;
}

void validate(const fs::path& path, int count, bool short_glossaries = false) {
  auto query = load(path);
  for (int i = 0; i < count; ++i) {
    const auto terms = query.query(expression(i));
    require(terms.size() == 1 && terms.front().glossaries.size() == 1, "missing term " + expression(i));
    require(terms.front().glossaries.front().glossary == glossary(i, short_glossaries), "glossary round trip " + expression(i));
    require(terms.front().score == i, "term score changed");
  }
  const auto term = query.query("食べる").front();
  require(term.frequencies.size() == 1 && term.frequencies.front().frequencies.front().value == 42,
          "frequency changed");
  require(term.pitches.size() == 1 && term.pitches.front().pitch_positions == std::vector<int>{2},
          "pitch changed");
  const auto kanji = query.query_kanji("食");
  require(kanji.entries.size() == 1 && kanji.entries.front().definitions == std::vector<std::string>{"eat"},
          "kanji changed");
  require(query.get_styles().size() == 1 && query.get_styles().front().styles == ".storage-test { color: red; }",
          "styles changed");
  const auto media = query.get_media_file("StorageFixture", "example.bin");
  require(std::string(media.begin(), media.end()) == "storage-media", "media changed");
  Deinflector deinflector;
  Lookup lookup(query, deinflector);
  const auto results = lookup.lookup("食べました");
  require(!results.empty() && results.front().term.expression == "食べる" &&
          results.front().term.glossaries.front().glossary == glossary(0, short_glossaries), "deinflected lookup changed");
}

uintmax_t storage_size(const fs::path& path) {
  uintmax_t size = 0;
  for (const auto& entry : fs::directory_iterator(path)) if (entry.is_regular_file()) size += entry.file_size();
  return size;
}

void run(const fs::path& root) {
  const auto legacy = root / "legacy/StorageFixture";
  require(fs::exists(legacy / ".hoshidicts_3"), "legacy fixture not generated");
  validate(legacy, term_count);
  for (bool low_ram : {false, true}) {
    const auto output = root / (low_ram ? "low-ram" : "normal");
    import_zip(root / "large.zip", output, low_ram);
    const auto path = output / "StorageFixture";
    require(fs::exists(path / ".hoshidicts_4") && fs::exists(path / "dict.zstd"), "training did not succeed");
    require(!fs::exists(path / ".hoshidicts_3"), "stale v3 marker");
    validate(path, term_count);
    require(storage_size(path) < storage_size(legacy), "trained storage did not shrink this fixture");
    std::cout << (low_ram ? "low-ram" : "normal") << " bytes=" << storage_size(path)
              << " legacy_bytes=" << storage_size(legacy) << '\n';
  }

  const auto compressed = root / "normal/StorageFixture";
  auto mixed = load(compressed);
  mixed.add_term_dict(legacy.string());
  std::vector<std::future<void>> workers;
  for (int thread = 0; thread < 4; ++thread) {
    workers.push_back(std::async(std::launch::async, [&mixed] {
      for (int i = 0; i < 100; ++i) {
        const auto terms = mixed.query(expression(i));
        require(terms.size() == 1 && terms.front().glossaries.size() == 2, "mixed dictionaries lost definitions");
        for (const auto& entry : terms.front().glossaries) require(entry.glossary == glossary(i), "mixed glossary");
      }
    }));
  }
  for (auto& worker : workers) worker.get();

  const auto corrupt = root / "corrupt";
  fs::copy(compressed, corrupt, fs::copy_options::recursive);
  fs::remove(corrupt / "dict.zstd");
  require(load(corrupt).query("食べる").empty(), "missing compression dictionary accepted");
  { std::ofstream f(corrupt / "dict.zstd", std::ios::binary); f << "invalid dictionary"; }
  require(load(corrupt).query("食べる").empty(), "corrupt compression dictionary accepted");
  { std::ofstream f(corrupt / "dict.zstd", std::ios::binary | std::ios::trunc); }
  require(load(corrupt).query("食べる").empty(), "empty compression dictionary accepted");

  for (const auto* fixture : {"small.zip", "tiny.zip"}) {
    const auto output = root / fixture;
    // Use a different directory from the input ZIP.
    const auto destination = root / (std::string(fixture) + "-output");
    import_zip(output, destination, true);
    const auto path = destination / "StorageFixture";
    require(fs::exists(path / ".hoshidicts_3") && !fs::exists(path / "dict.zstd"), "small fixture fallback");
    validate(path, std::string(fixture) == "small.zip" ? 1 : 8, std::string(fixture) == "tiny.zip");
  }

  const auto replacement = root / "replacement";
  import_zip(root / "large.zip", replacement, true);
  import_zip(root / "small.zip", replacement, true);
  auto path = replacement / "StorageFixture";
  require(fs::exists(path / ".hoshidicts_3") && !fs::exists(path / ".hoshidicts_4") &&
          !fs::exists(path / "dict.zstd"), "v4 to v3 replacement retained stale files");
  validate(path, 1);
  require(load(path).query("term10").empty(), "replacement retained old terms");
  import_zip(root / "large.zip", replacement, true);
  require(!fs::exists(path / ".hoshidicts_3") && fs::exists(path / ".hoshidicts_4"), "v3 to v4 replacement");
  validate(path, term_count);

  import_zip(root / "metadata.zip", root / "metadata", true);
  path = root / "metadata/MetadataFixture";
  require(fs::exists(path / ".hoshidicts_3") && !fs::exists(path / "dict.zstd"), "metadata-only format changed");
  require(load(path).query_kanji("食").entries.size() == 1, "metadata-only kanji lost");
  std::cout << "PASS: legacy/new round trips, metadata, media, lookup, mixed concurrent queries, "
               "missing/corrupt dictionaries, fallback and replacement imports\n";
}
#endif

int main(int argc, char** argv) {
  try {
    if (argc == 4) import_zip(argv[1], argv[2], std::string(argv[3]) == "low");
#ifndef LEGACY_IMPORT_ONLY
    else if (argc == 2) run(argv[1]);
#endif
    else throw std::runtime_error("usage: storage_tests FIXTURE_ROOT or legacy_import ZIP OUTPUT low");
  } catch (const std::exception& e) {
    std::cerr << "FAIL: " << e.what() << '\n';
    return 1;
  }
}
