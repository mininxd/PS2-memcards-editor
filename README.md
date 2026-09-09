# PS2 Memory Card Reader & Editor for Android

A modern, standalone PlayStation 2 Memory Card Reader, Manager, and Savegame Editor for Android (SDK 30 / Android 11+), designed with Jetpack Compose and Material You (Material 3).

---

## Overview

This project is a dedicated **PS2 Memory Card Reader and Editor** designed to easily browse, manage, backup, format, and **copy savegames** between memory cards and storage. Whether managing saves for emulators (PCSX2, AetherSX2, NetherSX2) or real PS2 hardware via USB/OTG adapters, this app provides full-featured memory card manipulation without needing a PC.

---

## Features

- **Memory Card Reader & Browser**:
  - Direct reading and parsing of PS2 Superblocks (Page 0, 340 bytes), Indirect FAT tables, and cluster allocation chains.
  - Full support for both **ECC format** (528 bytes/page with 16-byte Reed-Solomon/Hamming parity spare area) and **RAW format** (512 bytes/page).
  - Opens memory card images: `.ps2`, `.mc2`, `.mcd`, `.raw`, `.bin`, `.vmc`.
  - Reads PCSX2 Folder Memory Cards (`_pcsx2_superblock` and hierarchical directory trees).

- **Savegame Copying & Editor Tools**:
  - **Copy & Transfer Savegames**: Seamlessly copy and transfer save files between memory cards, folders, and external storage.
  - **Save Extraction & PSU Archive Support**: Pack and unpack standard EMS / PS2SaveBuilder `.psu` files and compressed `.zip` archives.
  - **3D Save Icon Rendering**: Decodes PS2 `.icn` 16-bit RGB1555 texture data directly into high-resolution Android Bitmaps for save card thumbnails.
  - **Shift-JIS & PS2 Japanese Title Decoding**: Converts CP932 / Shift-JIS / Full-width Japanese and Western titles from `icon.sys` into clean UTF-8 text.
  - **`icon.sys` Inspector**: View game titles, subtitles, 3D icon filenames, copy protection status, ambient/lighting direction vectors, and background colors.
  - **Card Formatting**: Create and format fresh 8MB, 16MB, 32MB, 64MB, and 128MB memory cards with standard Sony PS2 geometry.
  - **ECC Converter**: Convert between RAW (512B/page) and ECC (528B/page) formats.
  - **Hex Inspector**: Built-in hex viewer to inspect any individual file or raw block bytes.

- **Material You UI Design**:
  - Dynamic Color theming on Android 12+ (SDK 31+) with retro PlayStation deep blue & cyan accents on Android 11 (SDK 30).
  - Storage usage indicator bar with animated progress and free cluster diagnostics.
  - Search by game title or directory ID (e.g. `BASLUS-21445`, `SLUS-20946`).
  - Filter chips (All, PS2, PS1, Protected) and sorting (Name, Date, Size).
  - Smooth bottom sheets and modal dialogs.

- **Targeted for Android 11 / SDK 30**:
  - Full Storage Access Framework (SAF) integration for opening and exporting files seamlessly across internal storage, SD cards, and USB OTG drives.

---

## Project Structure

```
├── .github/
│   └── workflows/
│       └── build.yml               # GitHub Actions CI with workflow_dispatch & push triggers
├── app/
│   ├── build.gradle.kts            # Configured for SDK 30 / Android 11 & Compose Material 3
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml # Targets SDK 30, intent-filters for .ps2/.psu files
│       │   ├── java/com/ps2/memcard/
│       │   │   ├── core/           # Pure Kotlin PS2 Memory Card File System Engine
│       │   │   │   ├── Ps2Memcard.kt          # Superblock, FAT, directory, cluster I/O
│       │   │   │   ├── Ps2SuperBlock.kt       # Superblock layout, geometry, verification
│       │   │   │   ├── Ps2DirectoryEntry.kt   # Directory entry flags, timestamps, metadata
│       │   │   │   ├── Ps2Ecc.kt              # Parity table & Hamming ECC calculation
│       │   │   │   ├── Ps2Save.kt             # High-level save folder representation
│       │   │   │   ├── Ps2IconSys.kt          # icon.sys parser (titles, lighting, icons)
│       │   │   │   ├── Ps2IconDecoder.kt      # 3D .icn texture decoder & bitmap renderer
│       │   │   │   ├── Ps2ShiftJis.kt         # Shift-JIS & full-width text decoder
│       │   │   │   ├── PsuHandler.kt          # PSU/EMS archive packer and unpacker
│       │   │   │   ├── FolderMemcardHandler.kt# PCSX2 folder memory card converter
│       │   │   │   └── MemcardFormatter.kt    # Formatting 8MB to 128MB cards
│       │   │   └── ui/             # Jetpack Compose & Material You UI Layer
│       │   │       ├── MainActivity.kt
│       │   │       ├── MemcardViewModel.kt
│       │   │       ├── theme/
│       │   │       │   ├── Color.kt
│       │   │       │   ├── Theme.kt
│       │   │       │   └── Type.kt
│       │   │       ├── components/
│       │   │       │   ├── AppHeader.kt
│       │   │       │   ├── SaveCard.kt
│       │   │       │   ├── StorageBar.kt
│       │   │       │   ├── SaveDetailModal.kt
│       │   │       │   ├── CreateCardDialog.kt
│       │   │       │   ├── FormatCardDialog.kt
│       │   │       │   ├── CardStatsDialog.kt
│       │   │       │   ├── ConvertCardDialog.kt
│       │   │       │   └── HexViewerDialog.kt
│       │   │       └── screens/
│       │   │           ├── MainScreen.kt
│       │   │           └── EmptyStateScreen.kt
│       │   └── res/
│       └── test/
│           └── java/com/ps2/memcard/
│               └── Ps2MemcardTest.kt
├── reference/                      # Preserved C++ memory card source & filesystem docs
│   ├── PS2-MemoryCardFileSystem.htm
│   ├── MemoryCardFile.cpp
│   ├── MemoryCardFolder.cpp
│   └── MemoryCardProtocol.cpp
├── gradle/
│   └── libs.versions.toml
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

---

## Building with GitHub Actions

The repository includes a GitHub Actions workflow configured for manual execution (`workflow_dispatch`) and automatic builds on pushes/pull requests.

To build manually:
1. Go to the **Actions** tab on your GitHub repository.
2. Select **Build PS2 Memory Card Editor APK**.
3. Click **Run workflow**, choose your build type (`release` or `debug`), and click **Run workflow**.
4. Once completed, download the generated APK from the **Artifacts** section!

---

## Credits & Acknowledgements

- **ARMSX2**: Special thanks to the **ARMSX2** project and team for their foundational work, mobile optimizations, and inspiration.
- **PCSX2 Dev Team**: For the original SIO/Memcard implementation and folder memory card specifications.
- **Ross Ridge**: For the PlayStation 2 Memory Card File System research, specifications, and `mymc` tool.

---

## License

GPL-3.0+
