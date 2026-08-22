package com.macher.autosell.sell;

import com.macher.autosell.config.AutoSellConfig;
import com.macher.autosell.config.SellMode;
import com.macher.autosell.config.TransferMethod;
import com.macher.autosell.util.CommandUtil;
import com.macher.autosell.util.TitleMatcher;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.HashSet;
import java.util.Set;

/**
 * The auto-sell state machine. Runs exclusively on the client tick thread.
 *
 * <pre>
 * IDLE --(items found)--> OPENING --(sell GUI appears)--> TRANSFERRING
 *   ^                        |                              |        |
 *   |                     (timeout)                   (all moved / stalled)
 *   +--> COOLDOWN <--------+                              |        |
 *   |                                                     v        v
 *   +------------------- WAIT_REOPEN (close mode) / WAIT_CYCLE (keep-open mode)
 * </pre>
 *
 * Safety invariants (see AGENTS.md — never break these):
 * <ol>
 *   <li>The cursor stack is returned before any GUI close or button click
 *       (closing with a held stack would drop the item).</li>
 *   <li>Only generic container screens that pass the title check are ever touched.</li>
 *   <li>Only the 36 hotbar/main inventory slots are ever moved; armor and offhand are
 *       never part of a chest screen handler anyway.</li>
 *   <li>Any abnormal condition (GUI replaced, timeout, disconnect) resets to IDLE.</li>
 * </ol>
 */
public final class AutoSellManager {
	private static final AutoSellManager INSTANCE = new AutoSellManager();

	public static AutoSellManager getInstance() {
		return INSTANCE;
	}

	/** Hotbar + main inventory slots (armor and offhand are deliberately excluded). */
	private static final int PLAYER_SLOTS = 36;
	private static final int IDLE_POLL_INTERVAL_TICKS = 20;
	private static final int OPEN_GUI_TIMEOUT_TICKS = 100;
	private static final int RETRY_COOLDOWN_TICKS = 100;
	private static final int TRANSFER_STALL_TICKS = 30;

	private enum State {
		IDLE, COOLDOWN, OPENING, TRANSFERRING, WAIT_REOPEN, WAIT_CYCLE
	}

	private final AutoSellConfig config = AutoSellConfig.get();
	private final TransferScheduler scheduler = new TransferScheduler();

	private boolean enabled;
	private State state = State.IDLE;
	private int timer;
	private int idlePollCounter;
	private int stallCounter;
	private int lastPlayerItemCount = -1;
	private int transferCountdown;
	/** Player-side handler slot a PICKUP transfer last took a stack from, for safe returns. */
	private int pickupOriginSlot = -1;
	private Screen screenAtCommand;

	private AutoSellManager() {
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void toggle(MinecraftClient client) {
		enabled = !enabled;
		resetState();
		if (client.player != null) {
			client.player.sendMessage(Text.translatable(enabled
					? "macherautosell.msg.enabled"
					: "macherautosell.msg.disabled"), true);
		}
		if (enabled) {
			// poll immediately on the next tick instead of waiting a full interval
			idlePollCounter = IDLE_POLL_INTERVAL_TICKS;
		}
	}

	public void onDisconnect() {
		resetState();
	}

	public void tick(MinecraftClient client) {
		if (client.player == null || client.interactionManager == null) {
			resetState();
			return;
		}
		if (!enabled) {
			return;
		}
		switch (state) {
			case IDLE -> tickIdle(client);
			case COOLDOWN -> tickCooldown();
			case OPENING -> tickOpening(client);
			case TRANSFERRING -> tickTransferring(client);
			case WAIT_REOPEN -> tickWaitReopen(client);
			case WAIT_CYCLE -> tickWaitCycle(client);
		}
	}

	private void resetState() {
		state = State.IDLE;
		timer = 0;
		idlePollCounter = 0;
		stallCounter = 0;
		lastPlayerItemCount = -1;
		transferCountdown = 0;
		pickupOriginSlot = -1;
		screenAtCommand = null;
	}

	private void tickCooldown() {
		if (--timer <= 0) {
			state = State.IDLE;
			timer = 0;
		}
	}

	private void tickIdle(MinecraftClient client) {
		if (++idlePollCounter < IDLE_POLL_INTERVAL_TICKS) {
			return;
		}
		idlePollCounter = 0;
		if (hasSellableItems(client)) {
			startCycle(client);
		}
	}

	private void startCycle(MinecraftClient client) {
		String command = CommandUtil.normalize(config.getSellCommand());
		if (command.isEmpty()) {
			feedback(client, "macherautosell.msg.empty_command");
			state = State.COOLDOWN;
			timer = RETRY_COOLDOWN_TICKS;
			return;
		}
		screenAtCommand = client.currentScreen;
		client.player.networkHandler.sendChatCommand(command);
		state = State.OPENING;
		timer = OPEN_GUI_TIMEOUT_TICKS;
	}

	private void tickOpening(MinecraftClient client) {
		Screen screen = client.currentScreen;
		// Only accept a screen that appeared after the command was sent; a GUI the
		// player had already opened is not the response to our command.
		if (screen != screenAtCommand && isSellGui(screen)) {
			beginTransferring();
			return;
		}
		if (--timer <= 0) {
			feedback(client, "macherautosell.msg.no_gui");
			state = State.COOLDOWN;
			timer = RETRY_COOLDOWN_TICKS;
		}
	}

	private void beginTransferring() {
		stallCounter = 0;
		lastPlayerItemCount = -1;
		transferCountdown = 0;
		pickupOriginSlot = -1;
		state = State.TRANSFERRING;
	}

	private void tickTransferring(MinecraftClient client) {
		if (!isSellGui(client.currentScreen)) {
			// The GUI was closed or replaced — never touch anything else.
			resetState();
			return;
		}
		GenericContainerScreenHandler handler = sellGuiHandler(client);
		int containerSlots = handler.slots.size() - PLAYER_SLOTS;

		if (!handler.getCursorStack().isEmpty()) {
			returnCursorStack(client, handler, containerSlots);
			return;
		}

		int items = countPlayerItems(handler, containerSlots);
		if (items == 0) {
			// Everything movable has been deposited: trigger the sell.
			finishDeposit(client, handler);
			return;
		}

		if (items == lastPlayerItemCount) {
			if (++stallCounter >= TRANSFER_STALL_TICKS) {
				// No progress for a while (sell GUI likely full): trigger the sell anyway.
				finishDeposit(client, handler);
				return;
			}
		} else {
			stallCounter = 0;
			lastPlayerItemCount = items;
		}

		if (transferCountdown > 0) {
			transferCountdown--;
			return;
		}
		transferCountdown = scheduler.nextDelayTicks(config.getTransferDelayTicks(), config.isRandomizeTransferDelay());
		transferBurst(client, handler, containerSlots);
	}

	private void transferBurst(MinecraftClient client, GenericContainerScreenHandler handler, int containerSlots) {
		int burst = config.getTransferBurst();
		// Local handler state updates only when the server responds, so slots already
		// clicked in this burst are excluded instead of clicked twice.
		Set<Integer> clickedPlayerSlots = new HashSet<>();
		Set<Integer> usedContainerSlots = new HashSet<>();
		boolean shift = config.getTransferMethod() == TransferMethod.SHIFT;
		for (int i = 0; i < burst; i++) {
			int source = findPlayerStackSlot(handler, containerSlots, clickedPlayerSlots);
			if (source < 0) {
				break; // nothing left to move
			}
			if (shift) {
				click(client, handler, source, SlotActionType.QUICK_MOVE);
				clickedPlayerSlots.add(source);
			} else {
				int target = findEmptyContainerSlot(handler, containerSlots, usedContainerSlots);
				if (target < 0) {
					break; // sell GUI is full; stall detection will trigger the sell
				}
				pickupOriginSlot = source;
				click(client, handler, source, SlotActionType.PICKUP); // pick the stack up
				click(client, handler, target, SlotActionType.PICKUP); // place it into the sell GUI
				pickupOriginSlot = -1;
				clickedPlayerSlots.add(source);
				usedContainerSlots.add(target);
			}
		}
	}

	private void finishDeposit(MinecraftClient client, GenericContainerScreenHandler handler) {
		timer = config.getReopenDelayTicks();
		if (config.getSellMode() == SellMode.CLOSE_GUI) {
			client.player.closeHandledScreen();
			state = State.WAIT_REOPEN;
		} else {
			int slot = clamp(config.getKeepOpenButtonSlot(), 0, handler.slots.size() - 1);
			click(client, handler, slot, SlotActionType.PICKUP);
			state = State.WAIT_CYCLE;
		}
	}

	private void tickWaitReopen(MinecraftClient client) {
		if (--timer > 0) {
			return;
		}
		if (hasSellableItems(client)) {
			startCycle(client);
		} else {
			state = State.IDLE;
		}
	}

	private void tickWaitCycle(MinecraftClient client) {
		if (--timer > 0) {
			return;
		}
		if (!hasSellableItems(client)) {
			// Nothing left to sell; idle polling resumes the cycle when new items appear.
			state = State.IDLE;
			return;
		}
		if (isSellGui(client.currentScreen)) {
			beginTransferring();
		} else {
			startCycle(client);
		}
	}

	private void returnCursorStack(MinecraftClient client, GenericContainerScreenHandler handler, int containerSlots) {
		// A stack only sits on the cursor mid-burst in PICKUP mode. Its origin slot is
		// empty on the server (we just took the stack from it), so returning is a plain
		// PICKUP click on that slot and cannot lose anything.
		int origin = pickupOriginSlot >= 0 && pickupOriginSlot < handler.slots.size()
				? pickupOriginSlot
				: findEmptyPlayerSlot(handler, containerSlots);
		if (origin < 0) {
			// Nowhere safe to return the stack: stop entirely instead of risking a drop.
			feedback(client, "macherautosell.msg.cursor_stuck");
			enabled = false;
			resetState();
			return;
		}
		click(client, handler, origin, SlotActionType.PICKUP);
		pickupOriginSlot = -1;
	}

	private boolean hasSellableItems(MinecraftClient client) {
		for (int i = 0; i < PLAYER_SLOTS; i++) {
			if (!client.player.getInventory().getStack(i).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private boolean isSellGui(Screen screen) {
		if (!(screen instanceof HandledScreen<?> handled)) {
			return false;
		}
		if (!(handled.getScreenHandler() instanceof GenericContainerScreenHandler)) {
			return false;
		}
		return TitleMatcher.matches(config.isGuiTitleCheckEnabled(), config.getExpectedGuiTitle(),
				handled.getTitle().getString());
	}

	private GenericContainerScreenHandler sellGuiHandler(MinecraftClient client) {
		HandledScreen<?> handled = (HandledScreen<?>) client.currentScreen;
		return (GenericContainerScreenHandler) handled.getScreenHandler();
	}

	private static int countPlayerItems(GenericContainerScreenHandler handler, int containerSlots) {
		int count = 0;
		for (int i = 0; i < PLAYER_SLOTS; i++) {
			if (!handler.getSlot(containerSlots + i).getStack().isEmpty()) {
				count++;
			}
		}
		return count;
	}

	private static int findPlayerStackSlot(GenericContainerScreenHandler handler, int containerSlots, Set<Integer> exclude) {
		for (int i = 0; i < PLAYER_SLOTS; i++) {
			int slot = containerSlots + i;
			if (!exclude.contains(slot) && !handler.getSlot(slot).getStack().isEmpty()) {
				return slot;
			}
		}
		return -1;
	}

	private static int findEmptyContainerSlot(GenericContainerScreenHandler handler, int containerSlots, Set<Integer> exclude) {
		for (int i = 0; i < containerSlots; i++) {
			if (!exclude.contains(i) && handler.getSlot(i).getStack().isEmpty()) {
				return i;
			}
		}
		return -1;
	}

	private static int findEmptyPlayerSlot(GenericContainerScreenHandler handler, int containerSlots) {
		for (int i = 0; i < PLAYER_SLOTS; i++) {
			if (handler.getSlot(containerSlots + i).getStack().isEmpty()) {
				return containerSlots + i;
			}
		}
		return -1;
	}

	private static void click(MinecraftClient client, ScreenHandler handler, int slot, SlotActionType type) {
		client.interactionManager.clickSlot(handler.syncId, slot, 0, type, client.player);
	}

	private void feedback(MinecraftClient client, String key) {
		if (client.player != null) {
			client.player.sendMessage(Text.translatable(key), true);
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
