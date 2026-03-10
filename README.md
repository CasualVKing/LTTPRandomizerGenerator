# LTTP Randomizer Generator

A utility that generates randomized *A Link to the Past* ROMs using the [alttpr.com](https://alttpr.com) API. Available for **Windows 11** and **Android** (Retroid Pocket 5+, Ayn Thor, and other Android 11+ devices).

> **You must supply your own legally-obtained ALttP base ROM.** The Japanese v1.0 "Zelda no Densetsu: Kamigami no Triforce" ROM is required (CRC32: `0x777AAC3F`).

---

## Features

### Randomizer Settings
- All alttpr.com randomizer settings exposed as dropdowns
- Named preset system — save your favourite settings with a custom name
- 7 built-in presets: **Vanilla**, **Quick Run**, **Casual Boots**, **Keysanity**, **All Mix**, **Beginner**, **Swordless**
- Auto-saves last-used settings between sessions
- Generates a shareable seed permalink

### Seed Input (Racing)
- Enter a seed hash or full alttpr.com permalink to use an existing seed
- Settings auto-populate and lock to read-only; cosmetics and sprites remain editable
- Copy button for easy seed sharing after generation
- Clear (✕) to return to normal generation mode

### Cosmetic Customization
- **Heart Beep Speed** — Off, Quarter, Half, Normal, Double
- **Heart Color** — Red, Blue, Green, Yellow
- **Menu Speed** — Half, Normal, Double, Triple, Quad, Instant
- **Quick Swap** — toggle item swap without going to the menu

### Sprite Browser
- Browse 600+ community-created Link sprites from [alttpr.com](https://alttpr.com)
- Search by name or author; star favorites that pin to the top
- Sprite list and preview images cached to disk — browser works offline after first load
- **Random All** — a surprise sprite is picked at generate time from the full list
- **Random Favorites** — same, but only from your starred sprites
- Sprite preview shown on the main screen after selection (Android)
- Selecting Default (Link) resets to the original sprite
- Android: scrollable header with floating Cancel and scroll-to-top buttons for small screens

### General
- Full feature parity across Windows and Android
- Self-contained — no Python, PHP, or other runtimes required
- ROM path and output folder remembered across sessions
- Seed permalink copy button for easy sharing
- Your ROM is never sent to any server

---

## Download

Get the latest release from the [Releases](../../releases) page:

| Platform | File |
|----------|------|
| Windows 11 | `LTTPRandomizerGenerator.exe` |
| Android | `LTTPRandomizerGenerator.apk` |

### Android Installation (Obtainium)

1. Install [Obtainium](https://github.com/ImranR98/Obtainium)
2. Add this repo URL: `https://github.com/JoshuaACurrier/LTTPRandomizerGenerator`
3. Obtainium will detect new APK releases and notify you of updates

Manual sideload: download the APK, enable *Install from unknown sources* in Android settings, then install.

---

## Usage

1. **Select your base ROM** — the Japanese v1.0 ALttP ROM (`.sfc`, `.smc`, or `.rom`)
2. **Select an output folder** where the randomized ROM will be saved
3. *(Optional)* **Load an existing seed** — paste a hash or alttpr.com URL and click **Load** to use someone else's seed (settings lock automatically)
4. **Pick a preset** or configure settings manually using the dropdowns
5. *(Optional)* Change cosmetics under **CUSTOMIZATION**, or pick a sprite from the **SPRITE** section
6. Hit **Generate ROM**
7. The app contacts alttpr.com, downloads the seed patch, applies it locally, and writes the output ROM
8. A shareable seed permalink is shown — click **Copy** to share, or click the link to open the seed page

---

## Settings Reference

### Randomizer Settings

| Setting | What it does |
|---------|-------------|
| **Glitches** | Logic glitches allowed (None → Major) |
| **Item Placement** | How aggressively items are spread (Basic / Advanced) |
| **Dungeon Items** | Whether dungeon keys/maps stay in their dungeon (Standard → Keysanity) |
| **Accessibility** | Logic guarantee: can beat game / reach all locations / none |
| **Goal** | Win condition (Defeat Ganon, Fast Ganon, All Dungeons, Pedestal) |
| **Tower / Ganon Open** | Crystals required to enter Ganon's Tower / fight Ganon |
| **World State** | Open, Standard, Inverted, or Retro |
| **Entrance Shuffle** | Randomize overworld/dungeon entrances |
| **Boss / Enemy Shuffle** | Randomize bosses or enemies |
| **Hints** | NPC hint stones on/off |
| **Weapons** | Randomized / Assured / Vanilla / Swordless |
| **Item Pool** | Normal / Hard / Expert / Crowd Control |
| **Item Functionality** | How powerful items are (Normal → Expert) |
| **Spoiler Log** | Include a spoiler log in the seed |
| **Pegasus Boots Start** | Start the game with Pegasus Boots in inventory |

### Presets

| Preset | Description |
|--------|-------------|
| Vanilla | Near-base-game experience — standard world state, vanilla weapons, no shuffles |
| Quick Run | Fast Ganon, 7/7 crystals, basic placement |
| Casual Boots | Open world, start with Pegasus Boots |
| Keysanity | All dungeon items (maps, compasses, keys, big keys) can appear anywhere |
| All Mix | Crossed entrance shuffle, full boss/enemy shuffle, Keysanity |
| Beginner | 100% location accessibility, hints on, no shuffle |
| Swordless | No swords — must use alternative weapons throughout |

---

## Build from Source

### Windows

**Prerequisites:** .NET 8 SDK

```bash
cd windows
dotnet build -c Debug          # development
dotnet publish -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true    # production EXE
```

Output: `windows/bin/Release/net8.0-windows/win-x64/publish/LTTPRandomizerGenerator.exe`

### Android

**Prerequisites:** Android Studio or Android SDK (API 34), JDK 17

Open `android/` as the project root in Android Studio and build from there, or:

```bash
cd android
./gradlew assembleRelease
```

Output: `android/app/build/outputs/apk/release/app-release-unsigned.apk`

> For a signed APK (required for newer Android versions to install without developer options), configure a keystore in `android/app/build.gradle` under `signingConfigs`.

---

## Releases (GitHub Actions)

Push a tag matching `v*.*.*` to trigger automatic builds:

```bash
git tag v0.1.0
git push origin v0.1.0
```

Both Windows EXE and Android APK are built and attached to the release automatically.

---

## How It Works

1. App sends your chosen settings to `POST https://alttpr.com/api/randomizer` (no ROM upload)
2. API returns a seed hash + a URL to a BPS base patch + seed-specific byte patches
3. App downloads the BPS patch and applies it to your local ROM
4. Seed-specific patches (item placement, enemy placement, etc.) are applied on top
5. Cosmetic patches are applied (heart color, menu speed, etc.)
6. Sprite is injected if selected
7. SNES checksum is recalculated
8. Output ROM is written to your chosen folder

Your ROM is never sent to any server.

---

## Credits

Seed generation powered by [alttpr.com](https://alttpr.com) — the official A Link to the Past Randomizer.

---

## Legal Disclaimer

This project is an independent, fan-made utility and is not affiliated with, endorsed by, or sponsored by Nintendo Co., Ltd., its subsidiaries, or any of its licensees. *The Legend of Zelda: A Link to the Past* is a registered trademark of Nintendo.

This software does not contain, distribute, or host any copyrighted ROM data, game assets, sprites, or other Nintendo intellectual property. Users must supply their own legally-obtained copy of the base ROM. The application functions solely as a patch generator — it sends user-selected settings to the [alttpr.com](https://alttpr.com) API, receives patch data, and applies those patches locally to the user's own ROM file. No ROM data is ever uploaded or transmitted.

All community-created sprites displayed in the sprite browser are fan-made works hosted by and sourced from [alttpr.com](https://alttpr.com). They are not official Nintendo assets.
