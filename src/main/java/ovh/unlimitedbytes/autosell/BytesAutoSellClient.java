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
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.widget.EditBoxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BytesAutoSellClient implements ClientModInitializer {
	public static final String MOD_ID = "bytes-auto-sell";
	public static final Logger LOGGER = LoggerFactory.getLogger("BytesAutoSell");

	/**
	 * Whether the toggle key is currently held, for the in-screen path. GLFW key
	 * repeat re-fires the press event (~30 Hz) without an action marker, so a
	 * repeat is recognized by "the key has not been released yet". Cleared on the
	 * matching release (tracked per screen) and by the tick handler whenever no
	 * screen is open, so a release outside any screen can never wedge it.
	 */
	private static boolean toggleKeyDown;

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
			if (client.currentScreen == null) {
				toggleKeyDown = false; // release outside any screen resets the hold state
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
		ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			ScreenKeyboardEvents.allowKeyPress(screen).register((current, input) -> {
				if (!ModKeybinds.toggleAutoSell().matchesKey(input)) {
					return true;
				}
				if (current.getFocused() instanceof TextFieldWidget
						|| current.getFocused() instanceof EditBoxWidget
						|| current instanceof AbstractSignEditScreen) {
					return true; // the keystroke belongs to text entry
				}
				if (current instanceof KeybindsScreen) {
					return true; // let the Controls screen capture the key for rebinding
				}
				if (toggleKeyDown) {
					return false; // OS key repeat while held: consumed, not a new press
				}
				toggleKeyDown = true;
				manager.toggle(client);
				return false; // consumed: the GUI must not also process it
			});
			ScreenKeyboardEvents.allowKeyRelease(screen).register((current, input) -> {
				if (ModKeybinds.toggleAutoSell().matchesKey(input)) {
					toggleKeyDown = false;
				}
				return true; // releases are always passed through
			});
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> manager.onDisconnect());
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> UpdateChecker.checkOnJoin(client));

		LOGGER.info("Bytes Auto Sell initialized");
	}
}
