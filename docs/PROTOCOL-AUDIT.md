# Protocol Legitimacy Audit

Goal: prove that every network interaction of Bytes Auto Sell is **byte-identical on
the wire** to the same action performed manually by a player — no custom packets, no
extra packets, no altered packet fields.

Audit basis: disassembly (`javap -c`) of the yarn-mapped Minecraft 1.21.11 client in
the Loom cache, cross-checked against the mod's complete egress inventory. The vanilla
call paths are identical in the 26.x ports (only the mapping names differ; the mojmap
names are listed alongside and were bytecode-verified against the 26.1 and 26.2 jars
during the port review).

## Egress inventory

The mod has exactly **three** kinds of code site that cause network traffic — one
command site, one centralized click helper, and three close sites:

| # | Site (yarn / 1.21.11) | Vanilla equivalent a player triggers |
|---|---|---|
| 1 | `AutoSellManager.startCycle` → `networkHandler.sendChatCommand(cmd)` | Typing `/sell` in chat and pressing Enter |
| 2 | `AutoSellManager.click` → `interactionManager.clickSlot(syncId, slot, button, type, player)` (every slot click in the mod funnels through this one helper) | Left-clicking / shift-clicking a slot in a container GUI |
| 3 | `player.closeHandledScreen()` — at `finishDeposit` (Close GUI mode), at the GUI-full/button flush recovery, and at `safeRecover` (after an unexpected error, own screen only, empty cursor only) | Closing a container GUI (E / Esc) |

Everything else in the mod (GUI title check, inventory reads, timing, config I/O,
keybinds, the settings screen, action-bar feedback) is client-local and sends nothing.

## Per-action equivalence

### 1. Command execution — identical to typing it in chat

Vanilla `ChatScreen` dispatches chat input starting with `/` via exactly:

```
ClientPlayNetworkHandler.sendChatCommand(chatText.substring(1))
```

(disassembled `ChatScreen`, 1.21.11). The mod calls the **same method** with the same
normalized command string (leading `/` stripped, trimmed).

`sendChatCommand` itself (disassembled) parses the command with Brigadier, then sends
either `CommandExecutionC2SPacket` (no signable arguments — the normal case for a sell
command) or `ChatCommandSignedC2SPacket` with argument signatures, timestamp, salt and
last-seen-message acknowledgment (commands with signable arguments like `/msg`).
Because the mod calls the vanilla method, packet construction, signing, bookkeeping
and the acknowledgment collector behave exactly as for a manual chat execution.

Mojmap (26.x): `ClientPacketListener.sendCommand(String)` — the same single method
vanilla's `ChatScreen` uses.

### 2. Slot clicks — identical packets, including the client-side prediction sync

Vanilla container click handling (`HandledScreen.onMouseClick`) reduces to exactly:

```
ClientPlayerInteractionManager.clickSlot(handler.syncId, slotId, button, slotActionType, player)
```

The mod's `click()` helper passes the identical five arguments:

- transfer click: `button=0`, `SlotActionType.QUICK_MOVE` — the same call a player's
  shift-click produces;
- pickup/place click: `button=0`, `SlotActionType.PICKUP` — the same call a player's
  left-click produces;
- keep-open sell button: `button=0`, `SlotActionType.PICKUP` on the button slot —
  indistinguishable from clicking that button by hand.

`clickSlot` (disassembled) additionally: (a) runs `ScreenHandler.onSlotClick` locally
(the client-side prediction), then (b) builds the changed-slot sync map from the
handler state and the carried stack hash, and (c) sends `ClickSlotC2SPacket`. Because
the mod never constructs packets itself and always enters through this vanilla method,
the prediction and the packet payload (including the synchronized slot contents) are
produced by vanilla code and match a manual click exactly.

Mojmap (26.x): `MultiPlayerGameMode.handleContainerInput(containerId, slotId, button,
ContainerInput, Player)` — bytecode-verified in the 26.x jars to be the exact method
`AbstractContainerScreen.slotClicked` uses for manual clicks, building the identical
`ServerboundContainerClickPacket` with the same local prediction. `SlotActionType`
values map to `ContainerInput.PICKUP` / `ContainerInput.QUICK_MOVE`.

### 3. GUI close — identical to pressing E/Esc

`ClientPlayerEntity.closeHandledScreen()` (disassembled) sends
`CloseHandledScreenC2SPacket(syncId)` and closes the screen — the exact method vanilla
calls when a player closes a container GUI.

Mojmap (26.x): `LocalPlayer.closeContainer()`.

### 4. Feedback messages — no traffic at all

`player.sendMessage(Text, true)` routes to `MessageHandler.onGameMessage` — a purely
client-side overlay/action-bar display. Mojmap (26.x): `sendOverlayMessage(Component)`
(routes to the chat listener's overlay handler; no packet).

## The one remaining (non-protocol) difference: timing

Packet **contents** are always identical. Packet **inter-arrival timing** is not:
a human emits at most a handful of clicks per second, while the mod can emit up to
`transferBurst` quick-moves — or `2 × transferBurst` pickup/place clicks — per
`transferDelayTicks`. With the defaults (burst 10, delay 1) that is up to ~100–200
click packets per second while items actually move, which is far above sustained
human click rates.

This is inherent to any automation and addressable by configuration, not by protocol:
raise *Item Transfer Speed*, lower *Transfer Burst*, and enable *Randomize Transfer
Delay* for a human-like cadence. No server-side packet validation distinguishes the
mod's packets from manual ones; only statistical click-rate heuristics (anticheat
plugins) could, which is what the timing settings exist for.

## Maintaining this guarantee

Rule for all future changes (also an invariant in AGENTS.md): the mod must only cause
network traffic through the vanilla client methods listed above — `sendChatCommand`
(`sendCommand`), `clickSlot` (`handleContainerInput`), `closeHandledScreen`
(`closeContainer`) — and must never construct, modify, or schedule raw packets
itself.

One sanctioned exception outside the Minecraft protocol: the update check
(`update/UpdateChecker.java`) performs a single async HTTPS GET to the GitHub
releases API on server join (opt-out in the config, 5 s timeout, no retries,
silent on error). It is plain TLS web traffic, not Minecraft protocol traffic,
and adds no packets to the game connection.
