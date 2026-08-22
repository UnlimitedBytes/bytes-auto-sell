# Macher Auto Sell

A client-side Fabric mod for **Minecraft 1.21.11** that automatically sells your
inventory through your server's sell GUI.

> [!NOTE]
> Automated selling may be against the rules on some servers — check before use.

## Requirements

- Minecraft 1.21.11 (Java 21+)
- [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api)
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu) — adds a config button for this mod

## Usage

| Keybind (vanilla Controls menu) | Default | Action |
|---|---|---|
| Open Settings | `O` | Opens the Macher Auto Sell settings screen |
| Toggle Auto Sell | `K` | Enables/disables auto-selling (action bar feedback) |

While enabled, the mod polls your inventory. When it contains items it:

1. Runs the configured sell command (default `/sell`) — the server opens its sell GUI.
2. Moves all hotbar + main inventory stacks (never armor/offhand) into the GUI using
   the configured transfer method.
3. Completes the cycle in the configured **Sell Mode**:
   - **Close GUI** — closes the GUI (the server sells on close) and, if items remain
     or new ones arrive, reopens it after the Reopen Delay.
   - **Keep Open** — keeps the GUI open, clicks the configured button slot (default 35)
     to sell, then deposits the next batch after the Reopen Delay.

## Settings

All settings are available in-game (`O` keybind, or via Mod Menu) and stored in
`config/macher-auto-sell.json`.

| Setting | Default | Description |
|---|---|---|
| Sell Command | `/sell` | Command that opens the sell GUI (with or without leading `/`) |
| Sell Mode | Close GUI | How a sell cycle completes (see above) |
| Transfer Method | SHIFT | `SHIFT` = shift-clicks stacks; `PICKUP` = cursor pickup/place into empty slots |
| Item Transfer Speed (ticks) | 1 | Delay between transfer steps — lower is faster |
| Transfer Burst (stacks per tick) | 1 | Stacks moved per step — higher is faster |
| Randomize Transfer Delay | OFF | Randomizes the transfer delay (1x–2x the base) for a less robotic cadence |
| Reopen Delay (ticks) | 60 (3 s) | Delay between sell cycles |
| GUI Title Check | OFF | Only interact with a GUI whose title exactly matches the expected title |
| Expected GUI Title | *(empty)* | Title to match when the check is enabled |
| Keep-Open Button Slot | 35 | Slot clicked to sell in Keep Open mode |

**Tip:** with the GUI Title Check disabled, the mod will treat *any* chest-like GUI
that appears right after the sell command as the sell GUI. On servers with other
chest GUIs, enable the check and set the exact sell GUI title.

## Building

```bash
./gradlew build   # jar in build/libs/
./gradlew test    # unit tests only
```

## Development workflow

See [AGENTS.md](AGENTS.md): `main` holds QA-approved releases, `dev` is the
integration branch, features go through `feature/*` branches with a harsh code
review before merging into `dev`, and a QA gate before releasing to `main`.

## License

[MIT](LICENSE)
