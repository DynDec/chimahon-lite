<div align="center">

<img width="200" height="200" src="./app/src/main/res/drawable/chimahon.png" alt="Chimahon icon" />
<h1 align="center">Chimahon Lite</h1>

**A local-first manga reader for language learning.**

[![GitHub downloads](https://img.shields.io/github/downloads/sohilsayed/chimahon/latest/total?label=Latest%20Downloads&labelColor=27303D&color=0D1111&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/sohilsayed/chimahon/releases/latest)

</div>

Chimahon Lite is a focused fork of Chimahon. It keeps the manga library, reader, local OCR, Hoshidicts dictionary lookup, and Anki workflow while making the filesystem the source of truth for manga discovery.

## How it works

- The Browse tab opens the local manga source directly.
- Choose or replace the manga folder from Browse’s folder action or Data & storage settings.
- Each immediate subfolder is a manga. New folders are added to the library automatically; removed folders leave the database and reading history intact but are removed from the library.
- The app-owned `local` directory remains the fallback when no folder has been selected.

Folder selection uses Android’s document-tree storage access so a user-owned directory can be persisted across app restarts. See the [Android shared-storage guidance](https://developer.android.com/training/data-storage/shared/documents-files).

## Kept

- Manga library, entries, history, and the core reader.
- On-device manga OCR, OCR overlays in the reader, and an OCR Queue for progress, retry, and cancellation.
- Hoshidicts-backed dictionary lookup and vocabulary mining.
- Anki export and related media capture for manga reading.
- Dictionary, appearance, reader, backup, and tracking settings that remain relevant to manga use.

## Removed from the active product

- Online Mihon extensions and extension repositories.
- Anime browsing, library, downloads, and video-player surfaces.
- Novel reading and novel-specific navigation.
- The Updates tab and system-wide screen OCR.
- Downloaded Only mode, the Download Queue, and Downloads settings.

## Development

The first fork pass is intentionally verified with static inspection and compilation only; it does not emulate a device or run the app. Use the JDK bundled with Android Studio when building locally.

## Credits and license

- [Yomitan](https://github.com/yomidevs/yomitan): language-processing inspiration.
- [owocr](https://github.com/AuroraWright/owocr): OCR merge and reconstruction logic.
- [hoshidicts](https://github.com/Manhhao/hoshidicts/): dictionary engine.
- [manga-panel-detector-yolo26n](https://huggingface.co/leoxs22/manga-panel-detector-yolo26n): panel detection model.
- [Machita Chima (町田ちま)](https://www.youtube.com/channel/UCo7TRj3cS-f_1D9ZDmuTsjw): app name inspiration.

This project is licensed under the **GNU General Public License v3.0**. See [LICENSE](./LICENSE).
