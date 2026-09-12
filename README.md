# Нейрона / Neirona (ServerChan fork)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.12--1.21-green.svg)](https://minecraft.net)
[![Release](https://img.shields.io/github/v/release/engix3/ServerChan?label=release)](https://github.com/engix3/ServerChan/releases)

This is a fork of [himekifee/ServerChan](https://github.com/himekifee/ServerChan) — a friendly, AI-powered chat assistant for Minecraft servers that listens to chat, reacts to in-game events and executes commands through function calling.

This fork turns the assistant into **Нейрона (Neirona)** — a sarcastic, sharp-tongued AI companion — and adds a set of practical server-side tools on top of the upstream feature set.

> [简体中文](README_CN.md) | [日本語](README_JA.md) — upstream documentation, describes the original ServerChan feature set.

---

## What this fork adds

- **Нейрона persona** — default system prompt: bold, sarcastic, smart assistant. No colored emoji; kaomoji `(¬‿¬)`, `¯\_(ツ)_/¯` and symbols `✦ ★ ⚡ ☠` instead. Mild profanity allowed for emotional color.
- **Configurable chat prefix** — `bot.prefix` in the config (default `§d§l[Нейрона]§r `), legacy `&` codes are translated to `§` automatically, duplicate prefixes are suppressed.
- **Command output capture** — commands executed by the AI no longer run "blind": the console output (e.g. numbers from `/tps`) is buffered by a custom `CommandSender` and returned to the model in the tool result.
- **`get_server_metrics` tool** — live JSON metrics straight from the server: TPS (1m/5m/15m), online players (current/max/names), JVM memory usage and the caller's ping. No external utilities needed.
- **`web_search` tool** — async HTTP search via a self-hosted [SearXNG](https://docs.searxng.org/) instance (URL template in the config, top-3 snippets returned to the model).
- **`remember_fact` tool + long-term memory** — SQLite database (`plugins/ServerChan/memory.db`) stores facts about players; known facts are automatically injected into the system context for that player's requests.
- **Russian locale** — full `ru` translation of the plugin strings plus official vanilla Russian Minecraft event translations.
- **EN + RU config** — all `serverchan.yml` comments are bilingual (English/Russian).
- **Intention Checker global settings** — one flag (`intention.useGlobalSettings`) makes the intention checker reuse the main API key/base URL/model.
- **Quiet auth failures** — a missing or wrong API key produces one concise warning instead of a stack trace per message.

## Quick start (Paper / Purpur / Spigot)

1. Drop `serverchan-spigot-<version>-1.21-all.jar` into `plugins/` and restart the server.
2. Open `plugins/ServerChan/serverchan.yml` and set your API settings:

```yaml
[openai]
	apiKey = "sk-..."
	baseUrl = "https://api.openai.com/v1"   # keep the /v1 path
	model = "gpt-5.1"

[localization]
	locale = "ru"                           # Russian UI (en by default)
```

3. Run `/serverchan reload` in game (permission: `serverchan.admin`, default: ops).

Tools available to the model out of the box:

| Tool | Purpose |
|------|---------|
| `ExecuteMinecraftCommands` | Runs server commands (restricted to the configured admin by the default prompt) |
| `get_server_metrics` | TPS / online / JVM memory / ping as JSON |
| `web_search` | SearXNG web search, top-3 results |
| `remember_fact` | Saves a lasting fact about the player to SQLite |

SearXNG instance URL (with JSON output enabled) is configured in `[webSearch]`. Player facts live in `plugins/ServerChan/memory.db`.

## Other loaders

Fabric, Forge and NeoForge builds are supported by the build system and share the same core logic. Bukkit-specific extras (metrics provider, command output sender) are wired for Spigot/Paper/Purpur; on mod loaders the metrics tool degrades gracefully.

## Building from source

```bash
# Spigot/Paper/Purpur plugin jar
./gradlew :spigot:shadowJar -PmcVer=1.21

# Merged jar for all loaders (fabric, forge, neoforge, spigot)
./gradlew mergeJars -PmcVer=1.21
```

Artifacts land in `spigot/build/libs/` and `build/forgix/` respectively. Requires JDK 21 for MC 1.21 profiles (older Minecraft profiles build with JDK 8+).

## Configuration

`serverchan.yml` is generated on first start and documents every option in English and Russian. Highlights:

- `[openai]` — API key, base URL, model, temperature, system prompt
- `[intention]` — when the AI should respond, `useGlobalSettings` to reuse the main API settings
- `[bot]` — `prefix`, `adminName` (server commands are only executed on the admin's request), timezone, context size
- `[webSearch]` — SearXNG URL template with `{query}`, result count
- `[memory]` — enable/disable the SQLite long-term memory

## Credits & license

- Original project: [himekifee/ServerChan](https://github.com/himekifee/ServerChan) — all upstream credits apply.
- This fork: maintained by [engix3](https://github.com/engix3).
- Licensed under **GPL-3.0** (see [LICENSE](LICENSE)).
