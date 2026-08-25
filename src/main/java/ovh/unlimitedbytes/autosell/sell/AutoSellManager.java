package ovh.unlimitedbytes.autosell.sell;

import ovh.unlimitedbytes.autosell.config.AutoSellConfig;
import ovh.unlimitedbytes.autosell.config.SellMode;
import ovh.unlimitedbytes.autosell.config.TransferMethod;
import ovh.unlimitedbytes.autosell.util.CommandUtil;
import ovh.unlimitedbytes.autosell.util.TitleMatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * The auto-sell state machine. Runs exclusively on the client tick thread.
 *
 * <pre>
 * IDLE --(items found)--> OPENING --(sell GUI appears)--> TRANSFERRING
 *  ^                       |                                 |         |
 *  |            (timeout -> COOLDOWN with capped            (all moved  (stalled:
 *  |             exponential backoff, retry forever)         or stalled)  recovery)
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
 * Never-fail policy: while connected to a server the mod never disables itself and
 * never crashes the client. Failed cycle starts (no GUI / empty command) retry forever
 * with a capped exponential backoff (see {@link RetryBackoff}). A sell GUI that closes
 * or is replaced while being worked triggers a short cooldown and a fresh cycle — the
 * toggle keybind works inside screens too (except during text entry), so the player
 * always retains control. A sell GUI that repeatedly accepts nothing (typically: it is full) is never
 * fatal either: Keep Open mode grants the configured sell button extra clicks and then
 * closes and reopens the GUI (on servers that sell a chest's contents on close this
 * flushes it), and Close GUI mode keeps cycling since every reopen starts fresh. A
 * sell button that only picks stacks up likewise escalates to the close-and-reopen
 * recovery instead of disabling. A cursor stack that cannot be returned is parked in
 * the sell GUI (sold with the rest) or, if even that is impossible, retried until a
 * slot frees up — the GUI is never closed while the cursor holds an item. A disconnect
 * resets the cycle but preserves the toggle; selling resumes on the next server join.
 * As a final guarantee, the whole tick is wrapped in a catch-all: any unexpected
 * throwable resets the current cycle, is logged, and selling continues on later ticks.
 *
 * Protocol legitimacy (docs/PROTOCOL-AUDIT.md): the only network traffic is caused
 * by the vanilla methods sendCommand, handleContainerInput and closeContainer.
 *
 * Safety invariants (see AGENTS.md — never break these):
 * <ol>
 *   <li>The cursor stack is returned (or parked in the sell GUI) before any GUI close
 *       or button click, and after a button click that picked a stack up. A GUI is
 *       never closed while the cursor holds an item — that would drop it.</li>
 *   <li>Only the exact screen instance accepted as the sell GUI is ever touched
 *       (reference identity on top of the optional title check), and only slots
 *       within the container's own region are used as button slots.</li>
 *   <li>Only the 36 hotbar/main inventory slots are ever moved; armor and offhand are
 *       never part of a chest screen menu anyway.</li>
 *   <li>Fail resilient: abnormal conditions recover (reopen, retry, backoff) instead
 *       of disabling; the tick is exception-proofed; every slot click is bounds- and
 *       null-checked before it reaches vanilla.</li>
 * </ol>
 */
public final class AutoSellManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("BytesAutoSell");

	private static final AutoSellManager INSTANCE = new AutoSellManager();

	public static AutoSellManager getInstance() {
		return INSTANCE;
	}

	/** Hotbar + main inventory slots (armor and offhand are deliberately excluded). */
	private static final int PLAYER_SLOTS = 36;
	private static final int IDLE_POLL_INTERVAL_TICKS = 20;
	private static final int OPEN_GUI_TIMEOUT_TICKS = 100;
	/**
	 * A sell GUI response is only accepted within this many ticks after the command
	 * when the title check is disabled; a container screen appearing later is presumed
	 * opened by the player and must never be hijacked (chest/barrel screens share the
	 * ChestMenu type, so type alone cannot prove provenance — only
	 * the exact title can). With the title check enabled the title settles the
	 * question, and the full command timeout is accepted so high ping gets its
	 * generous window there.
	 */
	private static final int OPEN_ACCEPT_WINDOW_TICKS = 20;
	/** Cooldown after the sell GUI vanished (closed or replaced) mid-cycle. */
	private static final int GUI_CLOSED_RETRY_COOLDOWN_TICKS = 40;
	private static final int MIN_TRANSFER_STALL_TICKS = 30;
	/** Consecutive unproductive cycles before the GUI-full/button recovery kicks in. */
	private static final int MAX_REJECTED_CYCLES = 3;
	/** Extra clicks on the configured sell button granted by GUI-full recovery before the flush reopen. */
	private static final int SELL_BUTTON_RETRIES = 3;
	/**
	 * After this many loaded-cursor ticks, a throttled "still waiting for a free slot"
	 * message is shown; the return itself keeps retrying forever and never disables.
	 */
	private static final int MAX_CURSOR_RETURN_TICKS = 100;
	/** Interval (in loaded-cursor ticks) between throttled feedback messages. */
	private static final int CURSOR_STUCK_FEEDBACK_TICKS = 200;
	/** One return-click attempt every N ticks while a cursor stays loaded. */
	private static final int CURSOR_RETURN_RETRY_TICKS = 10;
	/**
	 * Ticks to wait after a sell-button click before interpreting a loaded cursor as a
	 * real pickup: the client predicts the click locally, so on servers that cancel the
	 * click while selling, the cursor only clears after one round trip — which can take
	 * a full second or more on a high-ping connection.
	 */
	private static final int BUTTON_GRACE_TICKS = 20;
	/**
	 * Minimum ticks between full error logs and user-visible recovery messages when
	 * the catch-all trips repeatedly — a persistently throwing path must not flood
	 * the log or the action bar at 20 Hz while the client keeps running.
	 */
	private static final int ERROR_FEEDBACK_THROTTLE_TICKS = 600;

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
	/** Ticks spent so far trying to return (or park) a loaded cursor stack. */
	private int cursorReturnTicks;
	/** Remaining GUI-full recovery clicks on the configured sell button. */
	private int sellButtonRetries;
	/** Set when GUI-full or button recovery should close+reopen the sell GUI after the retry clicks. */
	private boolean guiFlushPending;
	private boolean buttonRemapNotified;
	private Screen screenAtCommand;
	/**
	 * The exact screen instance this mod accepted as the sell GUI (from the command
	 * response) or kept open (Keep Open mode). Only this instance is ever interacted
	 * with again — a container screen the player opened later is a different object
	 * and must never be touched, even with the title check disabled. A toggle
	 * preserves the provenance while that exact screen stays open (so re-enabling
	 * inside it resumes selling); once it is closed the provenance is gone and a
	 * later container is never adopted.
	 */
	private Screen keptOpenScreen;
	/** Tracks world presence so a resumed session can be acknowledged once. */
	private boolean wasInWorld;
	/** Ticks remaining before the catch-all may log/notify again (error throttle). */
	private int errorFeedbackCooldown;

	private AutoSellManager() {
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void toggle(Minecraft client) {
		// Preserve the sell GUI's provenance across the toggle while that exact
		// screen instance is still open: toggling off and back on inside the sell
		// GUI then resumes selling in it instead of idling until it is closed.
		// Identity decides — a different screen the player opened later is never
		// adopted, and once the GUI is closed the provenance is gone for good.
		Screen openSellGui = client.gui.screen() == keptOpenScreen ? keptOpenScreen : null;
		enabled = !enabled;
		resetState();
		keptOpenScreen = openSellGui;
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

	/**
	 * A disconnect resets the running cycle but preserves the toggle: selling resumes
	 * automatically on the next server join. The user's stop (toggle off) of course
	 * stays off.
	 */
	public void onDisconnect() {
		resetState();
	}

	public void tick(Minecraft client) {
		if (errorFeedbackCooldown > 0) {
			errorFeedbackCooldown--;
		}
		try {
			tickStateMachine(client);
		} catch (Throwable t) {
			// Nothing inside the state machine may ever take the client down. Reset
			// the cycle, log, and keep selling on later ticks. A persistently
			// throwing path is throttled so the log and the action bar are not
			// flooded at 20 Hz; the state reset itself always runs.
			boolean notify = errorFeedbackCooldown <= 0;
			if (notify) {
				errorFeedbackCooldown = ERROR_FEEDBACK_THROTTLE_TICKS;
				LOGGER.error("Unexpected error in the auto-sell state machine; resetting the cycle", t);
			} else {
				LOGGER.debug("Recurring auto-sell state machine error (throttled)", t);
			}
			safeRecover(client, notify);
		}
	}

	private void tickStateMachine(Minecraft client) {
		if (client.player == null || client.gameMode == null) {
			resetState();
			wasInWorld = false;
			return;
		}
		if (!wasInWorld) {
			wasInWorld = true;
			if (enabled) {
				feedback(client, Component.translatable("bytesautosell.msg.resumed"));
			}
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
		if (keptOpenScreen != null && client.gui.screen() == keptOpenScreen) {
			beginTransferring();
			return;
		}
		// Never send the sell command while the player has any other screen open:
		// the command response would be indistinguishable from it. This also keeps
		// the mod idle (and stoppable with the toggle keybind) while the player
		// browses any screen.
		if (client.gui.screen() != null) {
			state = State.IDLE;
			return;
		}
		String command = CommandUtil.normalize(config.getSellCommand());
		if (command.isEmpty()) {
			startFailure(client, "bytesautosell.msg.empty_command");
			return;
		}
		screenAtCommand = client.gui.screen();
		client.player.connection.sendCommand(command);
		state = State.OPENING;
		timer = OPEN_GUI_TIMEOUT_TICKS;
	}

	private void tickOpening(Minecraft client) {
		Screen screen = client.gui.screen();
		// Only accept a screen that appeared promptly after the command was sent (or
		// any time during the window when the title check is enabled); a GUI appearing
		// late with the check disabled is not accepted, so a chest the player opened
		// manually is never hijacked.
		boolean acceptWindowOpen = timer > OPEN_GUI_TIMEOUT_TICKS - acceptWindowTicks();
		if (acceptWindowOpen && screen != screenAtCommand && isSellGui(screen)) {
			keptOpenScreen = screen;
			beginTransferring();
			return;
		}
		if (--timer <= 0) {
			startFailure(client, "bytesautosell.msg.no_gui");
		}
	}

	/**
	 * How long after the command a container screen is still accepted as the sell GUI.
	 * Without the title check, container type cannot prove provenance (chests and
	 * barrels use the same menu), so the window stays tight: a screen appearing
	 * later is presumed player-opened and is never hijacked. With the exact-title
	 * check the title itself is the proof, so high ping gets the full timeout.
	 */
	private int acceptWindowTicks() {
		return config.isGuiTitleCheckEnabled() ? OPEN_GUI_TIMEOUT_TICKS : OPEN_ACCEPT_WINDOW_TICKS;
	}

	/**
	 * A cycle start failed: retry forever with a capped exponential backoff. Never
	 * disables — a stopped bot loses far more than a retry, and a flaky/high-ping
	 * connection routinely needs several attempts.
	 */
	private void startFailure(Minecraft client, String messageKey) {
		feedback(client, Component.translatable(messageKey));
		state = State.COOLDOWN;
		timer = RetryBackoff.cooldownTicks(++startFailures);
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
		if (client.gui.screen() != keptOpenScreen || sellGuiMenu(client) == null) {
			// The sell GUI vanished while this mod was working it: it was closed or
			// replaced by the player (or the server). Recover with a short cooldown
			// and a fresh cycle instead of disabling — with the screen gone the
			// toggle keybind works again, so the player can still stop the mod in
			// the gap; and if another screen is open, startCycle waits until it is
			// closed before sending anything.
			recoverAfterGuiClosed(client);
			return;
		}
		ChestMenu menu = sellGuiMenu(client);
		int containerSlots = menu.slots.size() - PLAYER_SLOTS;

		if (!menu.getCarried().isEmpty()) {
			// Never give up on the cursor: keep returning (or parking) it until it is
			// empty. A GUI is never closed while it holds a stack.
			cursorReturnTicks++;
			if (cursorReturnTicks > MAX_CURSOR_RETURN_TICKS && cursorReturnTicks % CURSOR_STUCK_FEEDBACK_TICKS == 0) {
				feedback(client, Component.translatable("bytesautosell.msg.cursor_stuck_retrying"));
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
			// a recovery — a stopped bot loses far more than a retry ever could.
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
			keptOpenScreen = client.gui.screen();
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
		int slot = Math.min(configured, Math.max(0, containerSlots - 1));
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
		if (client.gui.screen() != keptOpenScreen || sellGuiMenu(client) == null) {
			// Same recovery as TRANSFERRING: the kept-open sell GUI is gone (player or
			// server closed it). With the screen gone the toggle keybind works, so the
			// player can stop the mod in the cooldown gap; otherwise selling resumes.
			recoverAfterGuiClosed(client);
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
				// full GUI; they must not feed the button-failure budget — the
				// flush reopen is the real verdict on whether selling works.
				boolean recovering = sellButtonRetries > 0 || guiFlushPending;
				if (!recovering) {
					buttonFailures++;
					buttonFailedThisWait = true;
				}
			}
			cursorReturnTicks++;
			if (cursorReturnTicks > MAX_CURSOR_RETURN_TICKS && cursorReturnTicks % CURSOR_STUCK_FEEDBACK_TICKS == 0) {
				feedback(client, Component.translatable("bytesautosell.msg.cursor_stuck_retrying"));
			}
			// Invariant 1: return the stack (with backoff — the server may be
			// rejecting the clicks); this parks it in the sell GUI or keeps retrying
			// if there is nowhere safe to put it. The GUI is never closed while the
			// cursor is loaded, so nothing can be dropped.
			if (shouldRetryReturn()) {
				returnCursorStack(client, menu);
			}
			if (buttonFailures >= MAX_REJECTED_CYCLES) {
				// The configured button only picks stacks up: escalate to the
				// close-and-reopen recovery (it runs once the cursor is empty)
				// instead of disabling. Close GUI mode flushes by design; Keep Open
				// gets a fresh GUI.
				buttonFailures = 0;
				guiFlushPending = true;
				feedback(client, Component.translatable("bytesautosell.msg.button_recovering"));
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
			// GUI-full or button recovery: the extra clicks did not help — close and
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
	 * Places the cursor stack back into an empty player-side slot. If the inventory is
	 * completely full, parks it in an empty sell-GUI slot instead — that is the same
	 * GUI the mod deposits into, so the stack is sold with the rest and never dropped.
	 * If nowhere is free (both sides full — possible while the server still owes
	 * confirmations on a high-ping connection), keeps waiting: a slot frees up as soon
	 * as any pending move is confirmed. Never disables and never closes the GUI with a
	 * loaded cursor.
	 */
	private void returnCursorStack(Minecraft client, ChestMenu menu) {
		int containerSlots = menu.slots.size() - PLAYER_SLOTS;
		int target = findEmptyPlayerSlot(menu, containerSlots);
		if (target < 0) {
			target = findEmptyContainerSlot(menu, containerSlots, Set.of());
			if (target < 0) {
				// Nowhere safe to put the stack yet; the throttled feedback comes from
				// the caller. Retry on a later tick.
				return;
			}
			feedback(client, Component.translatable("bytesautosell.msg.cursor_parked_in_gui"));
		}
		click(client, menu, target, ContainerInput.PICKUP);
	}

	/** Short cooldown, then a fresh cycle. Used whenever the sell GUI closed or was replaced. */
	private void recoverAfterGuiClosed(Minecraft client) {
		keptOpenScreen = null;
		guiFlushPending = false;
		sellButtonRetries = 0;
		buttonClickPending = false;
		buttonGraceTicks = 0;
		buttonFailedThisWait = false;
		// The interrupted cycle's measurements belong to the old GUI instance; the
		// reopened GUI starts with a clean slate.
		cursorReturnTicks = 0;
		rejectedCycles = 0;
		state = State.COOLDOWN;
		timer = GUI_CLOSED_RETRY_COOLDOWN_TICKS;
		feedback(client, Component.translatable("bytesautosell.msg.gui_closed_retry"));
	}

	/**
	 * Last-resort recovery after an unexpected throwable: reset the cycle (the toggle
	 * and the enabled state are preserved) and, when it is provably safe, close the
	 * sell GUI this mod was working (empty cursor) so the player is not left inside a
	 * half-worked screen. Never throws — the whole body is guarded so the catch-all
	 * itself cannot be defeated.
	 */
	private void safeRecover(Minecraft client, boolean notify) {
		try {
			Screen screen = client.gui.screen();
			boolean ours = screen != null && screen == keptOpenScreen;
			boolean cursorLoaded = false;
			if (screen instanceof AbstractContainerScreen<?> handled && handled.getMenu() != null) {
				cursorLoaded = !handled.getMenu().getCarried().isEmpty();
			}
			resetState();
			if (ours && !cursorLoaded && client.player != null) {
				// Only ever the mod's own accepted screen, and only with an empty
				// cursor — a foreign screen is left exactly as the player opened it.
				client.player.closeContainer();
			}
			if (notify) {
				feedback(client, Component.translatable("bytesautosell.msg.unexpected_error"));
			}
		} catch (Throwable closeError) {
			LOGGER.error("Recovery after an unexpected auto-sell error failed", closeError);
		}
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
		if (client.gui.screen() instanceof AbstractContainerScreen<?> handled
				&& handled.getMenu() instanceof ChestMenu generic) {
			return generic;
		}
		return null;
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

	/**
	 * Bounds- and null-checked slot click. Vanilla's local click prediction indexes
	 * the menu's slot list directly, so an out-of-range slot would crash the
	 * client; the guards make that impossible regardless of packet timing.
	 */
	private static void click(Minecraft client, AbstractContainerMenu menu, int slot, ContainerInput type) {
		if (client.gameMode == null || client.player == null || menu == null) {
			return;
		}
		if (slot < 0 || slot >= menu.slots.size()) {
			return;
		}
		client.gameMode.handleContainerInput(menu.containerId, slot, 0, type, client.player);
	}

	private void feedback(Minecraft client, Component message) {
		if (client.player != null) {
			client.player.sendOverlayMessage(message);
		}
	}
}
