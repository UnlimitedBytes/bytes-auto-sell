package ovh.unlimitedbytes.autosell.sell;

import ovh.unlimitedbytes.autosell.config.AutoSellConfig;
import ovh.unlimitedbytes.autosell.config.SellMode;
import ovh.unlimitedbytes.autosell.config.TransferMethod;
import ovh.unlimitedbytes.autosell.util.CommandUtil;
import ovh.unlimitedbytes.autosell.util.TitleMatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;

import java.util.HashSet;
import java.util.Set;

/**
 * The auto-sell state machine. Runs exclusively on the client tick thread.
 *
 * <pre>
 * IDLE --(items found)--> OPENING --(sell GUI appears)--> TRANSFERRING
 *  ^                       |                                 |         |
 *  |            (timeout -> COOLDOWN -> retry;           (all moved  (stalled:
 *  |             3 consecutive timeouts = disable)        or stalled)  recovery)
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
 * Failure budgets: three consecutive failed cycle starts (no GUI / empty command) or
 * three consecutive button clicks that pick a stack up instead of selling disable
 * auto-sell with a message. A sell GUI that accepts nothing across three cycles
 * (typically: it is full) is never fatal — recovery grants the configured sell button
 * up to {@link #SELL_BUTTON_RETRIES} extra clicks and then closes and reopens the GUI,
 * which flushes it on servers that sell a chest's contents on close (Close GUI mode
 * simply keeps cycling; its periodic reopen already provides the fresh GUI). Stopping
 * the bot would cost far more than a retry. The sell GUI closing or being replaced
 * while it is being worked disables auto-sell immediately: keybinds are unusable while
 * a screen is open, so silently continuing would lock the player out of stopping the
 * mod (player and server closes are deliberately treated the same way).
 *
 * Safety invariants (see AGENTS.md — never break these):
 * <ol>
 *   <li>The cursor stack is returned before any GUI close or button click, and after
 *       a button click that picked a stack up. Closing with a held stack would drop
 *       the item; if returning is impossible, auto-sell disables itself instead.</li>
 *   <li>Only the exact screen instance accepted as the sell GUI is ever touched
 *       (reference identity on top of the optional title check), and only slots
 *       within the container's own region are used as button slots.</li>
 *   <li>Only the 36 hotbar/main inventory slots are ever moved; armor and offhand are
 *       never part of a chest screen menu anyway.</li>
 *   <li>Fail safe: a vanished sell GUI disables auto-sell, timeouts fall back to a
 *       cooldown, and a disconnect always turns auto-sell off — it never resumes
 *       on its own after an abnormal condition.</li>
 * </ol>
 *
 * Protocol legitimacy (docs/PROTOCOL-AUDIT.md): the only network traffic is caused
 * by the vanilla methods sendCommand, click and closeContainer.
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
	/** Extra clicks on the configured sell button granted by GUI-full recovery before the flush reopen. */
	private static final int SELL_BUTTON_RETRIES = 3;
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
	/** Remaining GUI-full recovery clicks on the configured sell button. */
	private int sellButtonRetries;
	/** Set when GUI-full recovery should close+reopen the sell GUI after the retry clicks. */
	private boolean guiFlushPending;
	private boolean buttonRemapNotified;
	private Screen screenAtCommand;
	/**
	 * The exact screen instance this mod accepted as the sell GUI (from the command
	 * response) or kept open (Keep Open mode). Only this instance is ever interacted
	 * with again — a container screen the player opened later is a different object
	 * and must never be touched, even with the title check disabled. Toggling
	 * auto-sell off/on deliberately drops this provenance (resetState clears it);
	 * cycles then wait until the GUI is closed — fail-safe by design.
	 */
	private Screen keptOpenScreen;

	private AutoSellManager() {
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void toggle(Minecraft client) {
		enabled = !enabled;
		resetState();
		if (client.player != null) {
			client.player.sendOverlayMessage(Component.translatable(enabled
					? "bytesautosell.msg.enabled"
					: "bytesautosell.msg.disabled"));
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

	public void tick(Minecraft client) {
		if (client.player == null || client.gameMode == null) {
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
		sellButtonRetries = 0;
		guiFlushPending = false;
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

	private void tickIdle(Minecraft client) {
		if (++idlePollCounter < IDLE_POLL_INTERVAL_TICKS) {
			return;
		}
		idlePollCounter = 0;
		if (hasSellableItems(client)) {
			startCycle(client);
		}
	}

	private void startCycle(Minecraft client) {
		// The exact sell GUI this mod kept open from an earlier keep-open cycle may
		// still be open: resume it directly instead of sending the command. Identity
		// (not isSellGui) decides — a container the player opened later is a different
		// screen object and must never be touched. Without this resume, the open-screen
		// guard below would deadlock idle polling until the GUI is closed manually.
		if (keptOpenScreen != null && client.screen == keptOpenScreen) {
			beginTransferring();
			return;
		}
		// Never send the sell command while the player has any other screen open:
		// the command response would be indistinguishable from it.
		if (client.screen != null) {
			state = State.IDLE;
			return;
		}
		String command = CommandUtil.normalize(config.getSellCommand());
		if (command.isEmpty()) {
			startFailure(client, "bytesautosell.msg.empty_command");
			return;
		}
		screenAtCommand = client.screen;
		client.player.connection.sendCommand(command);
		state = State.OPENING;
		timer = OPEN_GUI_TIMEOUT_TICKS;
	}

	private void tickOpening(Minecraft client) {
		Screen screen = client.screen;
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
			startFailure(client, "bytesautosell.msg.no_gui");
		}
	}

	private void startFailure(Minecraft client, String messageKey) {
		if (++startFailures >= MAX_START_FAILURES) {
			disableWithFeedback(client, Component.translatable("bytesautosell.msg.disabled_failures"));
			return;
		}
		feedback(client, Component.translatable(messageKey));
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
		sellButtonRetries = 0;
		guiFlushPending = false;
		state = State.TRANSFERRING;
	}

	private void tickTransferring(Minecraft client) {
		if (client.screen != keptOpenScreen) {
			// The sell GUI vanished while this mod was working it: it was closed or
			// replaced by the player (or the server). Disable instead of silently
			// continuing — while any screen is open the toggle keybind cannot be
			// used, so a silent restart loop would lock the user out of stopping
			// auto-sell.
			disableWithFeedback(client, Component.translatable("bytesautosell.msg.disabled_closed"));
			return;
		}
		ChestMenu menu = sellGuiMenu(client);
		int containerSlots = menu.slots.size() - PLAYER_SLOTS;

		if (!menu.getCarried().isEmpty()) {
			if (++cursorReturnTicks > MAX_CURSOR_RETURN_TICKS) {
				disableWithFeedback(client, Component.translatable("bytesautosell.msg.cursor_stuck"));
				return;
			}
			if (shouldRetryReturn()) {
				returnCursorStack(client, menu);
			}
			return;
		}
		cursorReturnTicks = 0;

		int items = countPlayerItems(menu, containerSlots);
		if (items == 0) {
			// Everything movable has been deposited: trigger the sell.
			finishDeposit(client, menu, false);
			return;
		}

		if (lastPlayerItemCount < 0) {
			// first observation of this cycle
			lastPlayerItemCount = items;
			cycleStartItems = items;
		} else if (items == lastPlayerItemCount) {
			if (++stallCounter >= stallLimitTicks()) {
				// Secondary safety net behind the post-burst check: catches progress
				// that only becomes visible after the server reverts predicted
				// clicks (invisible to same-tick local state). Trigger the sell.
				finishDeposit(client, menu, true);
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
		transferBurst(client, menu, containerSlots);
		if (countPlayerItems(menu, containerSlots) == items) {
			// Not a single stack was deposited by this burst as observed locally
			// (the sell GUI is full, or nothing can be moved): trigger the sell
			// immediately instead of idling until the much slower stall window
			// below expires. Server-side reverts of predicted clicks only become
			// visible after a round trip and are left to that stall net.
			// Safe wrt invariant 1: a burst always ends with an empty cursor —
			// PICKUP breaks before picking when no free target slot exists.
			finishDeposit(client, menu, true);
		}
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

	private void transferBurst(Minecraft client, ChestMenu menu, int containerSlots) {
		int burst = config.getTransferBurst();
		// The client simulates slot clicks locally before sending the packet, so local
		// state is normally up to date; the exclusion sets additionally guard against
		// re-clicking slots whose local state could not be updated (e.g. server rejected).
		Set<Integer> clickedPlayerSlots = new HashSet<>();
		Set<Integer> usedContainerSlots = new HashSet<>();
		boolean shift = config.getTransferMethod() == TransferMethod.SHIFT;
		for (int i = 0; i < burst; i++) {
			int source = findPlayerStackSlot(menu, containerSlots, clickedPlayerSlots);
			if (source < 0) {
				break; // nothing left to move
			}
			if (shift) {
				click(client, menu, source, ContainerInput.QUICK_MOVE);
				clickedPlayerSlots.add(source);
			} else {
				int target = findEmptyContainerSlot(menu, containerSlots, usedContainerSlots);
				if (target < 0) {
					break; // sell GUI is full; the post-burst check triggers the sell
				}
				click(client, menu, source, ContainerInput.PICKUP); // pick the stack up
				click(client, menu, target, ContainerInput.PICKUP); // place it into the sell GUI
				clickedPlayerSlots.add(source);
				usedContainerSlots.add(target);
			}
		}
	}

	private void finishDeposit(Minecraft client, ChestMenu menu, boolean stalled) {
		int containerSlots = menu.slots.size() - PLAYER_SLOTS;
		if (stalled) {
			// A stall-terminated cycle only counts as rejected when the inventory did
			// not shrink at all during the whole cycle (server refuses/blacklists items
			// or the sell GUI is full). This is never fatal: hitting the budget starts
			// a recovery instead of disabling — a stopped bot loses far more than a
			// retry ever could.
			int items = countPlayerItems(menu, containerSlots);
			if (cycleStartItems >= 0 && items >= cycleStartItems) {
				if (++rejectedCycles >= MAX_REJECTED_CYCLES) {
					rejectedCycles = 0;
					feedback(client, Component.translatable("bytesautosell.msg.gui_full_recovering"));
					if (config.getSellMode() == SellMode.KEEP_OPEN) {
						sellButtonRetries = SELL_BUTTON_RETRIES;
						guiFlushPending = true;
					}
					// CLOSE_GUI needs no extra machinery: every cycle already closes
					// and reopens the GUI, which flushes it — just keep cycling.
				}
			} else {
				rejectedCycles = 0;
			}
		} else {
			rejectedCycles = 0;
		}

		timer = config.getReopenDelayTicks();
		if (config.getSellMode() == SellMode.CLOSE_GUI) {
			client.player.closeContainer();
			keptOpenScreen = null;
			state = State.WAIT_REOPEN;
		} else {
			keptOpenScreen = client.screen;
			click(client, menu, resolveButtonSlot(client, menu), ContainerInput.PICKUP);
			buttonClickPending = true;
			buttonFailedThisWait = false;
			buttonGraceTicks = BUTTON_GRACE_TICKS;
			state = State.WAIT_CYCLE;
		}
	}

	/** Configured keep-open button slot clamped into the container's own region; notifies once. */
	private int resolveButtonSlot(Minecraft client, ChestMenu menu) {
		int containerSlots = menu.slots.size() - PLAYER_SLOTS;
		int configured = config.getKeepOpenButtonSlot();
		int slot = Math.min(configured, containerSlots - 1);
		if (slot != configured && !buttonRemapNotified) {
			buttonRemapNotified = true;
			feedback(client, Component.translatable("bytesautosell.msg.button_slot_clamped", configured, slot));
		}
		return slot;
	}

	private void tickWaitReopen(Minecraft client) {
		if (--timer > 0) {
			return;
		}
		if (hasSellableItems(client)) {
			startCycle(client);
		} else {
			state = State.IDLE;
		}
	}

	private void tickWaitCycle(Minecraft client) {
		if (client.screen != keptOpenScreen) {
			// Same close policy as TRANSFERRING: the kept-open sell GUI is gone
			// (player closed it — keybinds are unusable inside a screen, so this is
			// the only way to honor a manual stop; server closes are indistinguishable
			// and fail safe the same way). Do not restart on our own.
			disableWithFeedback(client, Component.translatable("bytesautosell.msg.disabled_closed"));
			return;
		}
		// Still the exact GUI this mod kept open (identity, not isSellGui).
		ChestMenu menu = sellGuiMenu(client);
		if (!menu.getCarried().isEmpty()) {
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
				// Recovery clicks may legitimately pick a placeholder stack off a
				// full GUI; they must not feed the disabled_button budget — the
				// flush reopen is the real verdict on whether selling works.
				boolean recovering = sellButtonRetries > 0 || guiFlushPending;
				if (!recovering) {
					buttonFailures++;
					buttonFailedThisWait = true;
				}
			}
			if (++cursorReturnTicks > MAX_CURSOR_RETURN_TICKS) {
				disableWithFeedback(client, Component.translatable("bytesautosell.msg.cursor_stuck"));
				return;
			}
			// Invariant 1: return the stack (with backoff — the server may be
			// rejecting the clicks); this may itself disable auto-sell if there
			// is nowhere safe to put it.
			if (shouldRetryReturn()) {
				returnCursorStack(client, menu);
			}
			if (enabled && buttonFailures >= MAX_REJECTED_CYCLES) {
				disableWithFeedback(client, Component.translatable("bytesautosell.msg.disabled_button"));
			}
			// The reopen countdown stays paused on purpose while the grace window
			// runs and while the cursor is being returned — do not reorder this
			// with the timer decrement.
			return;
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

		if (sellButtonRetries > 0) {
			// GUI-full recovery, step 1: the last cycles ended without the server
			// accepting anything. Give the configured sell button a few more chances
			// before resorting to the reopen.
			sellButtonRetries--;
			click(client, menu, resolveButtonSlot(client, menu), ContainerInput.PICKUP);
			buttonClickPending = true;
			buttonGraceTicks = BUTTON_GRACE_TICKS;
			timer = config.getReopenDelayTicks();
			return;
		}
		if (guiFlushPending) {
			// GUI-full recovery, step 2: the extra clicks did not help — close and
			// reopen the GUI. On servers that sell a chest's contents on close this
			// flushes the full GUI; everywhere else it still provides a fresh,
			// empty GUI. Invariant 1 holds: the cursor is empty here (checked above).
			guiFlushPending = false;
			rejectedCycles = 0;
			buttonFailures = 0;
			client.player.closeContainer();
			keptOpenScreen = null;
			feedback(client, Component.translatable("bytesautosell.msg.gui_full_reopened"));
			state = State.WAIT_REOPEN;
			timer = config.getReopenDelayTicks();
			return;
		}

		if (!hasSellableItems(client)) {
			// Nothing left to sell; idle polling resumes the cycle when new items appear.
			state = State.IDLE;
			return;
		}
		// The kept-open GUI is still open (checked above): resume depositing into it.
		beginTransferring();
	}

	/**
	 * Places the cursor stack back into an empty player-side slot. Only called with a
	 * sell GUI open; if no empty slot exists, auto-sell disables itself instead of
	 * risking an item drop on the next close.
	 */
	private void returnCursorStack(Minecraft client, ChestMenu menu) {
		int containerSlots = menu.slots.size() - PLAYER_SLOTS;
		int target = findEmptyPlayerSlot(menu, containerSlots);
		if (target < 0) {
			disableWithFeedback(client, Component.translatable("bytesautosell.msg.cursor_stuck"));
			return;
		}
		click(client, menu, target, ContainerInput.PICKUP);
	}

	private void disableWithFeedback(Minecraft client, Component message) {
		enabled = false;
		resetState();
		feedback(client, message);
	}

	private boolean hasSellableItems(Minecraft client) {
		for (int i = 0; i < PLAYER_SLOTS; i++) {
			if (!client.player.getInventory().getItem(i).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private boolean isSellGui(Screen screen) {
		if (!(screen instanceof AbstractContainerScreen<?> handled)) {
			return false;
		}
		if (!(handled.getMenu() instanceof ChestMenu)) {
			return false;
		}
		return TitleMatcher.matches(config.isGuiTitleCheckEnabled(), config.getExpectedGuiTitle(),
				handled.getTitle().getString());
	}

	private ChestMenu sellGuiMenu(Minecraft client) {
		AbstractContainerScreen<?> handled = (AbstractContainerScreen<?>) client.screen;
		return (ChestMenu) handled.getMenu();
	}

	private static int countPlayerItems(ChestMenu menu, int containerSlots) {
		int count = 0;
		for (int i = 0; i < PLAYER_SLOTS; i++) {
			if (!menu.getSlot(containerSlots + i).getItem().isEmpty()) {
				count++;
			}
		}
		return count;
	}

	private static int findPlayerStackSlot(ChestMenu menu, int containerSlots, Set<Integer> exclude) {
		for (int i = 0; i < PLAYER_SLOTS; i++) {
			int slot = containerSlots + i;
			if (!exclude.contains(slot) && !menu.getSlot(slot).getItem().isEmpty()) {
				return slot;
			}
		}
		return -1;
	}

	private static int findEmptyContainerSlot(ChestMenu menu, int containerSlots, Set<Integer> exclude) {
		for (int i = 0; i < containerSlots; i++) {
			if (!exclude.contains(i) && menu.getSlot(i).getItem().isEmpty()) {
				return i;
			}
		}
		return -1;
	}

	private static int findEmptyPlayerSlot(ChestMenu menu, int containerSlots) {
		for (int i = 0; i < PLAYER_SLOTS; i++) {
			if (menu.getSlot(containerSlots + i).getItem().isEmpty()) {
				return containerSlots + i;
			}
		}
		return -1;
	}

	private static void click(Minecraft client, AbstractContainerMenu menu, int slot, ContainerInput type) {
		client.gameMode.handleContainerInput(menu.containerId, slot, 0, type, client.player);
	}

	private void feedback(Minecraft client, Component message) {
		if (client.player != null) {
			client.player.sendOverlayMessage(message);
		}
	}
}
