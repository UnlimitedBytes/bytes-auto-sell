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
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class BytesAutoSellClient implements ClientModInitializer {
	public static final String MOD_ID = "bytes-auto-sell";
	public static final Logger LOGGER = LoggerFactory.getLogger("BytesAutoSell");

	/**
	 * Screens that already carry the in-GUI toggle listener. Screens re-init on
	 * window resize and when re-shown after a child screen closes; without this
	 * guard each re-init would stack another listener and one key press would
	 * toggle the mod multiple times. Weak references: entries vanish with the
	 * screen instance once it is closed and collected.
	 */
	private static final Set<Screen> TOGGLE_KEYBIND_SCREENS =
			Collections.newSetFromMap(new WeakHashMap<>());

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
		// the sell GUI — but never while a text field is focused (typing).
		ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!TOGGLE_KEYBIND_SCREENS.add(screen)) {
				return;
			}
			ScreenKeyboardEvents.allowKeyPress(screen).register((current, input) -> {
				if (!ModKeybinds.toggleAutoSell().matchesKey(input)) {
					return true;
				}
				if (current.getFocused() instanceof TextFieldWidget) {
					return true; // the keystroke belongs to the text field
				}
				manager.toggle(client);
				return false; // consumed: the GUI must not also process it
			});
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> manager.onDisconnect());
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> UpdateChecker.checkOnJoin(client));

		LOGGER.info("Bytes Auto Sell initialized");
	}
}
