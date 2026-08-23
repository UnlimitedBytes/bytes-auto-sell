# AGENTS.md — Macher Auto Sell

Operating manual for AI agents (and humans) working in this repository.
Read this file completely before making any change.

## Project Overview

- **Macher Auto Sell** is a client-side **Fabric mod** for **Minecraft Java Edition 1.21.11** (Java 21, Yarn mappings).
- Mod id: `macher-auto-sell`. It automatically sells the player's inventory through a
  server-provided sell GUI (default command `/sell`), with configurable transfer methods,
  timings and safety checks.
- Two keybinds (configurable in the vanilla Controls screen):
  - Open settings menu (default `O`)
  - Toggle auto-sell on/off (default `K`)
- Optional Mod Menu integration: the mod's settings screen is reachable from Mod Menu.
- No mixins; pure Fabric API. All game interaction happens on the client tick thread.

## Repository Layout

```
gradle.properties          # all dependency/toolchain versions (single source of truth)
build.gradle               # fabric-loom-remap build (single main source set, classic layout)
src/main/java/com/macher/autosell/
    MacherAutoSellClient.java    # client entrypoint: config load, keybinds, tick wiring
    config/AutoSellConfig.java   # JSON config (load/save/sanitize/clamp)
    config/SellMode.java         # CLOSE_GUI | KEEP_OPEN
    config/TransferMethod.java   # SHIFT | PICKUP
    keybind/ModKeybinds.java     # keybind registration
    sell/AutoSellManager.java    # the auto-sell state machine (core of the mod)
    sell/TransferScheduler.java  # transfer delay + optional randomization
    util/CommandUtil.java        # sell-command normalization (pure, unit-tested)
    util/TitleMatcher.java       # GUI title check (pure, unit-tested)
    ui/AutoSellConfigScreen.java # in-game settings screen
    compat/ModMenuIntegration.java # Mod Menu entrypoint (only loaded if Mod Menu present)
src/main/resources/
    fabric.mod.json              # mod manifest (version expanded from gradle.properties)
    assets/macher-auto-sell/
        lang/en_us.json          # ALL user-facing strings
        icon.png
src/test/java/                  # unit tests for the pure logic classes
```

## Build & Test

```bash
./gradlew build    # compile + tests + remapped jar in build/libs/
./gradlew test     # unit tests only
```

- Requires a JDK able to compile `release = 21` (a newer JDK is fine).
- The first build downloads Gradle, Minecraft, mappings and dependencies — be patient.
- There is **no automated runtime test**: verifying the sell cycle itself needs a real
  client plus a server that provides a sell GUI. Runtime testing is part of the QA gate.

## Branching Model

| Branch   | Purpose | Stability guarantee |
|----------|---------|---------------------|
| `main`   | Releases only. Every merge into `main` is a tagged, QA-approved release. | Must always build; code is tested and reviewed. |
| `dev`    | Integration branch. | Mostly stable; may occasionally break. |
| `feature/<name>` | One logical change, branched **from `dev`**. | Untested until reviewed. |

**Hard rules**

1. Never commit directly to `main`. The only exception is repository meta changes
   (LICENSE, .gitignore, AGENTS.md) before the first feature lands.
2. Never merge anything into `dev` without an APPROVED harsh code review (below).
3. Never merge `dev` into `main` without a PASSED QA gate (below).
4. Every merge is performed `--no-ff` with a PR-style message, because until a remote
   with hosted pull requests exists, PRs are emulated as local merge commits.

### Stage 1 — Feature development → `dev` (code review gate)

1. `git checkout dev && git checkout -b feature/<name>`
2. Implement the change. Keep commits small and self-contained.
3. Self-check: `./gradlew build` must pass, including unit tests for any new pure logic.
4. Spawn a **harsh code-reviewer subagent** (general-purpose) on the branch diff.
   Give it the repository path, the branch name, and the review charter below.
   The reviewer is read-only — it must not modify code.
5. The reviewer returns `APPROVE` or `REJECT` with numbered findings
   (severity: BLOCKER / MAJOR / MINOR / NIT).
6. Fix **every BLOCKER and MAJOR** finding on the feature branch. Address MINORs or
   rebut them explicitly. Re-request review if any BLOCKER/MAJOR was fixed.
7. Merge PR-style:
   `git checkout dev && git merge --no-ff feature/<name> -m "PR #N: <title> (reviewed)"`
   and note the review verdict in the merge message body.
8. Do not delete the feature branch (it documents review history).

### Stage 2 — `dev` → `main` (release QA gate)

1. Decide the semantic version bump and update `mod_version` in `gradle.properties`.
2. Spawn a **QA subagent** (general-purpose) with the repository path.
   QA runs the full build and tests, inspects the jar, and executes the security
   checklist below. QA may run commands but must not modify code.
3. QA returns `PASS` or `FAIL` with findings.
4. On FAIL: fix on `dev` (directly or via a short-lived `fix/*` branch), then re-run QA.
5. On PASS:
   `git checkout main && git merge --no-ff dev -m "Release vX.Y.Z: <summary> (QA passed)"`
   then `git tag -a vX.Y.Z -m "Macher Auto Sell vX.Y.Z"`.

### Review charter (harsh reviewer)

The reviewer acts as a senior Minecraft/Fabric engineer with **zero tolerance**:
- Rejects on any BLOCKER or MAJOR finding. "It compiles" is not a quality bar.
- Checks: correctness of client inventory interaction (slot indexes, cursor handling,
  desync risks), thread-safety (client-thread-only game API calls), state machine
  soundness (every state has a safe exit; abnormal conditions reset to IDLE),
  item-loss impossibility, config validation and clamping, IO error handling,
  null-safety, naming, dead code, lang completeness, Fabric API idiom.
- Explicitly verifies the safety invariants listed below.

### QA checklist (release gate)

1. `./gradlew build` green (compile + tests + jar).
2. Jar contents sane: `fabric.mod.json` present with correct version, `environment: client`,
   entrypoints resolve, lang file packaged.
3. Security review: no credential harvesting, no network traffic beyond the Minecraft
   connection itself, no writes outside the mod's own config file, no runtime-obfuscated
   or reflected code paths, no keylogging beyond the two registered keybinds, the only
   command ever sent to the server is the configured sell command, no eval/deserialization
   of untrusted data.
4. Config audit: defaults match the specification, all values clamped, corrupt config
   file falls back to defaults instead of crashing.
5. Runtime hazard audit (code-level): cursor never holds an item when the GUI is closed
   or a sell button is clicked; no interaction with GUIs that fail the title check;
   state resets on disconnect.

## Code Standards

- Java 21 language level, Yarn mapping names (no mojmap names, no reflective MC access,
  no mixins unless truly unavoidable — currently zero).
- All Minecraft API calls stay on the client thread (tick/render). No extra threads.
- Pure, game-independent logic (command normalization, title matching, delay scheduling,
  config clamping) lives in small classes under `util`/`config` with unit tests.
- Every config value is clamped/validated before first use and after every load.
- Every user-facing string goes through `lang/en_us.json` — no hardcoded UI text.
- One logical change per feature branch; commit messages in imperative mood.

## Safety Invariants (never break these)

1. **Never drop items.** Never close a GUI or click a sell button while the cursor holds
   an item (vanilla would drop it). Return the cursor stack first; if impossible, abort
   the cycle and leave the GUI open.
2. **Only touch the sell GUI.** Interact only with `GenericContainerScreenHandler` screens
   (`ChestMenu` on 26.x), and only when the (optional) GUI title check passes. A blank
   expected title with the check enabled matches nothing, never everything.
3. **Never sell armor or offhand.** Only the 36 main inventory + hotbar slots are moved.
4. **Fail safe.** The sell GUI closing or being replaced while the mod is working it
   disables auto-sell immediately (keybinds are unusable while a screen is open, so
   silently continuing would lock the player out of stopping it; player and server
   closes are treated the same way). Timeouts reset the state machine to IDLE with a
   cooldown and re-sync with reality before acting again. Repeated failed starts or
   fully rejected cycles disable auto-sell with a message instead of looping forever,
   and a disconnect always turns it off.
5. **Protocol legitimacy** (see docs/PROTOCOL-AUDIT.md). All network traffic must go
   through the vanilla client methods — `sendChatCommand` (`sendCommand` on 26.x),
   `clickSlot` (`handleContainerInput` on 26.x), `closeHandledScreen` (`closeContainer`)
   — so every packet is byte-identical to manual execution. Never construct, modify, or
   schedule raw packets.

## Multi-Version Support

The mod supports multiple Minecraft versions, one branch per version:

| Minecraft | Branch | Java | Toolchain |
|---|---|---|---|
| 1.21.11 | `mc/1.21.11` (mirrors dev/main) | 21 | yarn mappings, `fabric-loom-remap` (obfuscated) |
| 26.1 | `mc/26.1` | 25 | no mappings (unobfuscated), `fabric-loom`, Mojang official names |
| 26.1.1 | `mc/26.1.1` | 25 | same |
| 26.1.2 | `mc/26.1.2` | 25 | same |
| 26.2 | `mc/26.2` | 25 | same |

Rules:

- `main`/`dev` are the primary development line (currently 1.21.11, yarn names).
  `mc/1.21.11` mirrors that line for uniform per-version branch names.
- The 26.x branches contain the same logic ported to Mojang official names
  (`MinecraftClient`→`Minecraft`, `clickSlot`→`click`, `SlotActionType`→`ClickType`,
  `GenericContainerScreenHandler`→`ChestMenu`, widgets `ButtonWidget`→`Button`,
  `TextFieldWidget`→`EditBox`, etc.) with per-version dependency pins in
  `gradle.properties` and the non-remap Loom plugin.
- Change flow: implement on `dev` (primary), then port to each affected `mc/*` branch
  by cherry-picking and resolving the mapping renames. Changes on `mc/*` branches
  require the same code-review gate and a green build before being pushed there.
- Version scheme: `mod_version` is identical across ports; artifact versions carry a
  `+mc<version>` suffix automatically (e.g. `1.0.0+mc26.2`). Release tags on `main`
  remain plain `vX.Y.Z`.

## Versioning

`MAJOR.MINOR.PATCH` (semver-ish): breaking config/behavior changes, features, fixes.
The version lives in `gradle.properties` (`mod_version`) and is expanded into
`fabric.mod.json` at build time. Release tags on `main` match it exactly.
