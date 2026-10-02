"""Generate deterministic Yomitan archives for native storage regression tests."""
import json
import pathlib
import sys
import zipfile

root = pathlib.Path(sys.argv[1])
root.mkdir(parents=True, exist_ok=True)
description = "This is a dictionary definition with shared vocabulary, grammar and example sentences."


def archive(name, count, title="StorageFixture", meta_only=False, short_glossaries=False):
    with zipfile.ZipFile(root / name, "w", zipfile.ZIP_DEFLATED) as z:
        def write(path, obj):
            z.writestr(path, json.dumps(obj, ensure_ascii=False, separators=(",", ":")))
        write("index.json", {"title": title, "format": 3, "revision": "storage-test", "isUpdatable": False})
        if not meta_only:
            terms = [["食べる" if i == 0 else f"term{i}", "たべる" if i == 0 else f"term{i}",
                      "", "v1" if i == 0 else "", i,
                      [str(i) if short_glossaries else f"Definition number {i}: {description}"], i, ""] for i in range(count)]
            for start in range(0, count, 2000):
                write(f"term_bank_{start // 2000 + 1}.json", terms[start:start + 2000])
        write("term_meta_bank_1.json", [["食べる", "freq", {"reading": "たべる", "frequency": 42}],
                                      ["食べる", "pitch", {"reading": "たべる", "pitches": [{"position": 2}]}]])
        write("kanji_bank_1.json", [["食", "ショク", "た.べる", "", ["eat"], {"grade": "2"}]])
        z.writestr("styles.css", ".storage-test { color: red; }")
        z.writestr("example.bin", b"storage-media")


archive("large.zip", 4000)
archive("small.zip", 1)
archive("tiny.zip", 8, short_glossaries=True)
archive("metadata.zip", 0, "MetadataFixture", True)
