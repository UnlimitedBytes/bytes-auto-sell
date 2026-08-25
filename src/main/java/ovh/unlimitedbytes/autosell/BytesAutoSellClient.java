package ovh.unlimitedbytes.autosell;

import ovh.unlimitedbytes.autosell.config.AutoSellConfig;
import ovh.unlimitedbytes.autosell.keybind.ModKeybinds;
import ovh.unlimitedbytes.autosell.sell.AutoSellManager;
import ovh.unlimitedbytes.autosell.ui.AutoSellConfigScreen;
import ovh.unlimitedbytes.autosell.update.UpdateChecker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BytesAutoSellClient implements ClientModInitializer {
	public static final String MOD_ID = "bytes-auto-sell";
	public static final Logger LOGGER = LoggerFactory.getLogger("BytesAutoSell");

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

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> manager.onDisconnect());
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> UpdateChecker.checkOnJoin(client));

		LOGGER.info("Bytes Auto Sell initialized");
	}
}
