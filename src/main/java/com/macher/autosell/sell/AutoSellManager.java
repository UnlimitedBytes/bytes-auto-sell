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
 *  ^                       |                                 |         |
 *  |            (timeout -> COOLDOWN -> retry;           (all moved  (stalled:
 *  |             3 consecutive timeouts = disable)        or stalled)  budget)
 *  |                                                   sell + wait the cycle delay
 *  |                                           CLOSE_GUI: WAIT_REOPEN  KEEP_OPEN: WAIT_CYCLE
 *  |                                                       |             |
 *  +------------------(no items left)----------------------+-------------+
 *                                    (items remain)          |
 *                                                           v
 *                                              startCycle -> OPENING, or resume
 *                                              TRANSFERRING if the GUI is still open
 * </pre>
 *
 * Failure budgets: three consecutive failed cycle starts (no GUI / empty command),
 * three consecutive stall-terminated cycles without inventory progress, or three
 * consecutive button clicks that pick a stack up instead of selling, disable
 * auto-sell with a message, instead of spamming the server forever.
 *
 * Safety invariants (see AGENTS.md — never break these):
 * <ol>
 *   <li>The cursor stack is returned before any GUI close or button click, and after
 *       a button click that picked a stack up. Closing with a held stack would drop
 *       the item; if returning is impossible, auto-sell disables itself instead.</li>
 *   <li>Only generic container screens that pass the title check are ever touched,
 *       and only slots within the container's own region are used as button slots.</li>
 *   <li>Only the 36 hotbar/main inventory slots are ever moved; armor and offhand are
 *       never part of a chest screen handler anyway.</li>
 *   <li>Any abnormal condition (GUI replaced, timeout, disconnect) resets to IDLE,
 *       and a disconnect always turns auto-sell off — it never resumes on its own.</li>
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
	/**
	 * A sell GUI response is only accepted within this many ticks after the command;
	 * a container screen appearing later is presumed opened by the player and is
	 * never treated as the sell GUI (hardening when the title check is disabled).
	 */
	private static final int OPEN_ACCEPT_WINDOW_TICKS = 20;
	private static final int RETRY_COOLDOWN_TICKS = 100;
	private static final int MIN_TRANSFER_STALL_TICKS = 30;
	private static final int MAX_START_FAILURES = 3;
	private static final int MAX_REJECTED_CYCLES = 3;
	private static final int MAX_CURSOR_RETURN_TICKS = 100;
	/** One return-click attempt every N ticks while a cursor stays loaded. */
	private static final int CURSOR_RETURN_RETRY_TICKS = 10;
	/**
	 * Ticks to wait after a sell-button click before interpreting a loaded cursor as a
	 * real pickup: the client predicts the click locally, so on servers that cancel the
	 * click while selling, the cursor only clears after one round trip.
	 */
	private static final int BUTTON_GRACE_TICKS = 10;

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
	private int cycleStartItems = -1;
	private int transferCountdown;
	private int startFailures;
	private int rejectedCycles;
	/** Consecutive button clicks whose cursor pickup persisted beyond the server-response grace window. */
	private int buttonFailures;
	/** Set when a sell-button click is issued; observed (and cleared) once the grace window expires. */
	private boolean buttonClickPending;
	/** Countdown after a button click during which a loaded cursor is not yet trusted. */
	private int buttonGraceTicks;
	/** Whether the current WAIT period observed a failed button click. */
	private boolean buttonFailedThisWait;
	/** Consecutive ticks spent trying to return a loaded cursor stack. */
	private int cursorReturnTicks;
	private boolean buttonRemapNotified;
	private Screen screenAtCommand;
	/**
	 * The exact screen instance this mod accepted as the sell GUI (from the command
	 * response) or kept open (Keep Open mode). Only this instance is ever interacted
	 * with again — a container screen the player opened later is a different object
	 * and must never be touched, even with the title check disabled.
	 */
	private Screen keptOpenScreen;

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

	/** Auto-sell never survives a server change; the user re-enables it deliberately. */
	public void onDisconnect() {
		enabled = false;
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
		cycleStartItems = -1;
		transferCountdown = 0;
		startFailures = 0;
		rejectedCycles = 0;
		buttonFailures = 0;
		buttonClickPending = false;
		buttonFailedThisWait = false;
		buttonGraceTicks = 0;
		cursorReturnTicks = 0;
		buttonRemapNotified = false;
		screenAtCommand = null;
		keptOpenScreen = null;
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
		// The exact sell GUI this mod kept open from an earlier keep-open cycle may
		// still be open: resume it directly instead of sending the command. Identity
		// (not isSellGui) decides — a container the player opened later is a different
		// screen object and must never be touched. Without this resume, the open-screen
		// guard below would deadlock idle polling until the GUI is closed manually.
		if (keptOpenScreen != null && client.currentScreen == keptOpenScreen) {
			beginTransferring();
			return;
		}
		// Never send the sell command while the player has any other screen open:
		// the command response would be indistinguishable from it.
		if (client.currentScreen != null) {
			state = State.IDLE;
			return;
		}
		String command = CommandUtil.normalize(config.getSellCommand());
		if (command.isEmpty()) {
			startFailure(client, "macherautosell.msg.empty_command");
			return;
		}
		screenAtCommand = client.currentScreen;
		client.player.networkHandler.sendChatCommand(command);
		state = State.OPENING;
		timer = OPEN_GUI_TIMEOUT_TICKS;
	}

	private void tickOpening(MinecraftClient client) {
		Screen screen = client.currentScreen;
		// Only accept a screen that appeared promptly after the command was sent; a
		// GUI appearing late (or one the player had already opened) is not accepted
		// as the command response.
		boolean acceptWindowOpen = timer > OPEN_GUI_TIMEOUT_TICKS - OPEN_ACCEPT_WINDOW_TICKS;
		if (acceptWindowOpen && screen != screenAtCommand && isSellGui(screen)) {
			keptOpenScreen = screen;
			beginTransferring();
			return;
		}
		if (--timer <= 0) {
			startFailure(client, "macherautosell.msg.no_gui");
		}
	}

	private void startFailure(MinecraftClient client, String messageKey) {
		if (++startFailures >= MAX_START_FAILURES) {
			disableWithFeedback(client, Text.translatable("macherautosell.msg.disabled_failures"));
			return;
		}
		feedback(client, Text.translatable(messageKey));
		state = State.COOLDOWN;
		timer = RETRY_COOLDOWN_TICKS;
	}

	private void beginTransferring() {
		startFailures = 0;
		stallCounter = 0;
		lastPlayerItemCount = -1;
		cycleStartItems = -1;
		transferCountdown = 0;
		// buttonFailures deliberately persists across cycles within one run; the
		// per-wait flags belong to the wait period that just ended.
		buttonClickPending = false;
		buttonFailedThisWait = false;
		state = State.TRANSFERRING;
	}

	private void tickTransferring(MinecraftClient client) {
		if (client.currentScreen != keptOpenScreen) {
			// The GUI was closed or replaced — never touch anything else.
			resetState();
			return;
		}
		GenericContainerScreenHandler handler = sellGuiHandler(client);
		int containerSlots = handler.slots.size() - PLAYER_SLOTS;

		if (!handler.getCursorStack().isEmpty()) {
			if (++cursorReturnTicks > MAX_CURSOR_RETURN_TICKS) {
				disableWithFeedback(client, Text.translatable("macherautosell.msg.cursor_stuck"));
				return;
			}
			if (shouldRetryReturn()) {
				returnCursorStack(client, handler);
			}
			return;
		}
		cursorReturnTicks = 0;

		int items = countPlayerItems(handler, containerSlots);
		if (items == 0) {
			// Everything movable has been deposited: trigger the sell.
			finishDeposit(client, handler, false);
			return;
		}

		if (lastPlayerItemCount < 0) {
			// first observation of this cycle
			lastPlayerItemCount = items;
			cycleStartItems = items;
		} else if (items == lastPlayerItemCount) {
			if (++stallCounter >= stallLimitTicks()) {
				// No progress for a while (sell GUI likely full): trigger the sell anyway.
				finishDeposit(client, handler, true);
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

	/**
	 * Stalls are only real once they outlast the longest possible gap between bursts
	 * (up to 2x the configured delay when randomized, see TransferScheduler) plus
	 * headroom for the server to respond to a burst.
	 */
	private int stallLimitTicks() {
		int maxGap = 2 * config.getTransferDelayTicks();
		return Math.max(MIN_TRANSFER_STALL_TICKS, 2 * maxGap + 10);
	}

	/** First return attempt immediately, then one every {@link #CURSOR_RETURN_RETRY_TICKS} loaded ticks. */
	private boolean shouldRetryReturn() {
		return cursorReturnTicks % CURSOR_RETURN_RETRY_TICKS == 1;
	}

	private void transferBurst(MinecraftClient client, GenericContainerScreenHandler handler, int containerSlots) {
		int burst = config.getTransferBurst();
		// The client simulates slot clicks locally before sending the packet, so local
		// state is normally up to date; the exclusion sets additionally guard against
		// re-clicking slots whose local state could not be updated (e.g. server rejected).
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
				click(client, handler, source, SlotActionType.PICKUP); // pick the stack up
				click(client, handler, target, SlotActionType.PICKUP); // place it into the sell GUI
				clickedPlayerSlots.add(source);
				usedContainerSlots.add(target);
			}
		}
	}

	private void finishDeposit(MinecraftClient client, GenericContainerScreenHandler handler, boolean stalled) {
		int containerSlots = handler.slots.size() - PLAYER_SLOTS;
		if (stalled) {
			// A stall-terminated cycle only counts as rejected when the inventory did
			// not shrink at all during the whole cycle (server refuses/blacklists items).
			int items = countPlayerItems(handler, containerSlots);
			if (cycleStartItems >= 0 && items >= cycleStartItems) {
				if (++rejectedCycles >= MAX_REJECTED_CYCLES) {
					disableWithFeedback(client, Text.translatable("macherautosell.msg.disabled_rejected"));
					return;
				}
			} else {
				rejectedCycles = 0;
			}
		} else {
			rejectedCycles = 0;
		}

		timer = config.getReopenDelayTicks();
		if (config.getSellMode() == SellMode.CLOSE_GUI) {
			client.player.closeHandledScreen();
			keptOpenScreen = null;
			state = State.WAIT_REOPEN;
		} else {
			keptOpenScreen = client.currentScreen;
			// Clamp into the container's own region: on small GUIs the configured slot
			// index may fall into the player inventory or beyond the GUI entirely.
			int configured = config.getKeepOpenButtonSlot();
			int slot = Math.min(configured, containerSlots - 1);
			if (slot != configured && !buttonRemapNotified) {
				buttonRemapNotified = true;
				feedback(client, Text.translatable("macherautosell.msg.button_slot_clamped", configured, slot));
			}
			click(client, handler, slot, SlotActionType.PICKUP);
			buttonClickPending = true;
			buttonFailedThisWait = false;
			buttonGraceTicks = BUTTON_GRACE_TICKS;
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
		if (client.currentScreen == keptOpenScreen) {
			// Still the exact GUI this mod kept open (identity, not isSellGui).
			GenericContainerScreenHandler handler = sellGuiHandler(client);
			if (!handler.getCursorStack().isEmpty()) {
				if (buttonGraceTicks > 0) {
					// The client predicts the button click locally: on servers that
					// cancel the click while selling, the cursor only clears after one
					// round trip. Do not interpret or touch a loaded cursor yet.
					buttonGraceTicks--;
					return;
				}
				// The cursor is still loaded beyond the grace window: the sell-button
				// click genuinely picked up a stack instead of selling. Count at most
				// one failure per issued click (buttonClickPending), never per tick —
				// a slot that never sells would otherwise juggle items between GUI
				// and inventory forever.
				if (buttonClickPending) {
					buttonClickPending = false;
					buttonFailures++;
					buttonFailedThisWait = true;
				}
				if (++cursorReturnTicks > MAX_CURSOR_RETURN_TICKS) {
					disableWithFeedback(client, Text.translatable("macherautosell.msg.cursor_stuck"));
					return;
				}
				// Invariant 1: return the stack (with backoff — the server may be
				// rejecting the clicks); this may itself disable auto-sell if there
				// is nowhere safe to put it.
				if (shouldRetryReturn()) {
					returnCursorStack(client, handler);
				}
				if (enabled && buttonFailures >= MAX_REJECTED_CYCLES) {
					disableWithFeedback(client, Text.translatable("macherautosell.msg.disabled_button"));
				}
				// The reopen countdown stays paused on purpose while the grace window
				// runs and while the cursor is being returned — do not reorder this
				// with the timer decrement.
				return;
			}
		}
		buttonGraceTicks = Math.max(0, buttonGraceTicks - 1);
		if (buttonGraceTicks == 0) {
			// A cursor first appearing after grace expiry cannot be the mod's click
			// (a real pickup is loaded continuously from the click and is counted on
			// the first grace-expired tick), so stop attributing loads to the button.
			buttonClickPending = false;
		}
		cursorReturnTicks = 0;
		if (--timer > 0) {
			return;
		}
		// The wait period ended. If the button click sold cleanly (no cursor load was
		// observed the whole wait), reset the failure counter; otherwise keep it.
		buttonClickPending = false;
		if (!buttonFailedThisWait) {
			buttonFailures = 0;
		}
		buttonFailedThisWait = false;
		if (!hasSellableItems(client)) {
			// Nothing left to sell; idle polling resumes the cycle when new items appear.
			state = State.IDLE;
			return;
		}
		if (client.currentScreen == keptOpenScreen) {
			beginTransferring();
		} else {
			startCycle(client);
		}
	}

	/**
	 * Places the cursor stack back into an empty player-side slot. Only called with a
	 * sell GUI open; if no empty slot exists, auto-sell disables itself instead of
	 * risking an item drop on the next close.
	 */
	private void returnCursorStack(MinecraftClient client, GenericContainerScreenHandler handler) {
		int containerSlots = handler.slots.size() - PLAYER_SLOTS;
		int target = findEmptyPlayerSlot(handler, containerSlots);
		if (target < 0) {
			disableWithFeedback(client, Text.translatable("macherautosell.msg.cursor_stuck"));
			return;
		}
		click(client, handler, target, SlotActionType.PICKUP);
	}

	private void disableWithFeedback(MinecraftClient client, Text message) {
		enabled = false;
		resetState();
		feedback(client, message);
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

	private void feedback(MinecraftClient client, Text message) {
		if (client.player != null) {
			client.player.sendMessage(message, true);
		}
	}
}
