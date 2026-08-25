package ovh.unlimitedbytes.autosell;

import ovh.unlimitedbytes.autosell.config.AutoSellConfig;
import ovh.unlimitedbytes.autosell.keybind.ModKeybinds;
import ovh.unlimitedbytes.autosell.sell.AutoSellManager;
import ovh.unlimitedbytes.autosell.ui.AutoSellConfigScreen;
import ovh.unlimitedbytes.autosell.update.UpdateChecker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BytesAutoSellClient implements ClientModInitializer {
	public static final String MOD_ID = "bytes-auto-sell";
	public static final Logger LOGGER = LoggerFactory.getLogger("BytesAutoSell");

	/**
	 * Debounce for the in-screen toggle: GLFW key-repeat re-fires the press event
	 * (~30 Hz while held) and the repeat action is not part of the event, so two
	 * presses within this window are treated as one physical press.
	 */
	private static final long IN_SCREEN_TOGGLE_DEBOUNCE_NS = 250_000_000L;
	private static long lastInScreenToggleNanos;

	@Override
	public void onInitializeClient() {
		AutoSellConfig.load();
		ModKeybinds.register();

		AutoSellManager manager = AutoSellManager.getInstance();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (ModKeybinds.openSettings().wasPressed()) {
				client.setScreen(AutoSellConfigScreen.create(client.currentScreen));
			}
			while (ModKeybinds.toggleAutoSell().wasPressed()) {
				manager.toggle(client);
			}
			manager.tick(client);
		});

		// Vanilla only routes key events to keybinds while no screen is open; the
		// sell GUI would otherwise lock the player out of stopping the mod. The
		// toggle keybind is therefore also intercepted inside screens — including
		// the sell GUI. Fabric replaces the per-screen event on every init (fires
		// BEFORE_INIT after wiping), so the listener is (re-)registered on EVERY
		// BEFORE_INIT — after a resize or a child-screen round trip the fresh
		// event would otherwise carry no listener at all.
		ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) ->
				ScreenKeyboardEvents.allowKeyPress(screen).register((current, input) -> {
					if (!ModKeybinds.toggleAutoSell().matchesKey(input)) {
						return true;
					}
					if (current.getFocused() instanceof TextFieldWidget
							|| current instanceof BookEditScreen
							|| current instanceof AbstractSignEditScreen) {
						return true; // the keystroke belongs to text entry
					}
					if (current instanceof KeybindsScreen) {
						return true; // let the Controls screen capture the key for rebinding
					}
					long now = System.nanoTime();
					if (now - lastInScreenToggleNanos < IN_SCREEN_TOGGLE_DEBOUNCE_NS) {
						return false; // key repeat: consumed, but not a new press
					}
					lastInScreenToggleNanos = now;
					manager.toggle(client);
					return false; // consumed: the GUI must not also process it
				}));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> manager.onDisconnect());
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> UpdateChecker.checkOnJoin(client));

		LOGGER.info("Bytes Auto Sell initialized");
	}
}
