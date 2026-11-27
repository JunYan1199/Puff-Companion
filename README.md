# Puff Companion (帕芙伙伴)

> A lively anime mouse-girl companion add-on for **Rats: Reboot** on **Minecraft 1.20.1 (Forge)** — featuring 421 Japanese voice lines, 21 local Galgame story chapters, 24 facial expressions, 5 unlockable outfits, rat squad command, and deep multi-mod crossovers.
>
> 面向 **Minecraft 1.20.1 (Forge)** 的 **Rats: Reboot（老鼠：重启）** 附属伙伴模组：一位可互动、可成长、可换装、可带队干活与协同作战的花枝鼠娘同伴 **帕芙（Puff）**，内置 421 条全日语配音、21 幕本地 Galgame 剧情树、24 种表情与多模组深度联动。

---

## Features / 核心特性

### 1. High-Detail 3D Companion & Expressive Mood System
- **24 Facial Expressions & 6 Ear Mood Shapes**: Puff dynamically reacts to combat, headpats, cheese, cats, hunger, weather, dimensions, and story beats.
- **5 Unlockable Outfits**: Adventure Outfit, Artisan Outfit, Starlight Dress, Swimsuit, and Wedding Dress — switchable anytime once unlocked.
- **Smooth Performance**: GPU VBO & bone-array caching in `SkinnedBodyLayer` keeps frame rates smooth.

### 2. Full Japanese Voiceover (421 Offline `.ogg` Lines)
- **100% Local Offline Voice Pack**: Every speech bubble, interaction, headpat, gift reaction, biome/boss alert, and all 21 Galgame story chapters are fully voiced in Japanese (with brother/sister address variants).
- **Smart Playback & Volume Slider**: Automatic voice interruption (no overlapping lines), Galgame Auto-Mode waits for each line to finish before advancing, and a built-in **0%–100% live voice volume slider** sits right inside Puff's interaction screen.

### 3. 21 Local Galgame Story Chapters & Interactive Story Tree
- Includes Prologue, 3 Main Chapters, 5 Side Stories, Proposal Finale, Post-Marriage Epilogue, 4 Branch/True Endings, and **6 Mod Crossover Stories**.
- Draggable & zoomable **Memory Story Tree** with custom pixel badges, route trackers, and replay support.
- Zero external API required for any built-in dialogue or story.

### 4. Practical Survival Companion & Rat Squad Commander
- **8 Work & Combat Modes**: Follow & Guard, Active Patrol, Spell Combat (Goety / Iron's Spellbooks), Ranged Archer, Auto Farm, Auto Lumberjack, Auto Fishing, and Ranch Care.
- **Home Mode & Secret Nest**: Set a home anchor for local base work, store loot in Puff's Secret Nest, and ride on your shoulder (`Shift` to hop off).
- **Leader Cheese Staff**: Recruit your tamed `Rats: Reboot` rats into Puff's squad, assign jobs (Lumberjack, Infinite Miner, Create Blueprint Builder, Farmer, Fisher, Patrol, Rancher), and manage members from the Squad UI.
- **Optional Free AI Chat**: Bring your own API key (Gemini / DeepSeek / OpenAI-compatible / local Ollama) in `config/puffcompanion-ai.json` for context-aware free chat that can also trigger Puff's expressions and commands.

---

## Dependencies / 前置依赖

### Required (必需)
- **Minecraft**: `1.20.1`
- **Minecraft Forge**: `47.1.0+`
- **Rats: Reboot** (`rats`): `8.1.5+`
- **Citadel** (`citadel`): `2.2+`
- **GeckoLib 4** (`geckolib`): `4.8.4` – `4.x`
- **Apothic Attributes** (`attributeslib`): `1.3+` (and its dependency **Placebo**)

### Optional Integrations (可选联动)
- **Curios API** — Dedicated Curios slots for Puff + Starlight Curio Slot Expander
- **Lootr** — Puff automatically opens nearby Lootr chests for her own share
- **Refined Storage** — RS Deposit Charm to auto-deposit Puff's nest items into your RS network
- **Create** — Blueprint building support for Puff's rat squad
- **Goety / Goety Revelation / Iron's Spellbooks / Travel Optics / Alex's Caves / L_Ender's Cataclysm** — Dedicated crossover stories, boss & biome bubbles, and combat spell casting

---

## Installation / 安装方法
1. Install Minecraft `1.20.1` with Forge `47.1.0+` and all required dependencies above.
2. Download `puffcompanion-1.20.1-1.0.0.jar` from Releases and drop it into your `mods` folder.
3. Launch the game and meet Puff right near your spawn!

---

## Building from Source / 从源码构建
Place the required dependency jars (`Rats-Reboot`, `citadel`, `geckolib`, `ApothicAttributes`, and optional soft-dep jars) into `libs/`, then run:
```bash
./gradlew build
```
The production jar will be generated in `build/libs/`.
