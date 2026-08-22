package com.macher.autosell;

import com.macher.autosell.config.AutoSellConfig;
import com.macher.autosell.keybind.ModKeybinds;
import com.macher.autosell.sell.AutoSellManager;
import com.macher.autosell.ui.AutoSellConfigScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MacherAutoSellClient implements ClientModInitializer {
	public static final String MOD_ID = "macher-auto-sell";
	public static final Logger LOGGER = LoggerFactory.getLogger("MacherAutoSell");

	@Override
	public void onInitializeClient() {
		AutoSellConfig.load();
		ModKeybinds.register();

		AutoSellManager manager = AutoSellManager.getInstance();
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			while (ModKeybinds.openSettings().consumeClick()) {
				minecraft.setScreenAndShow(AutoSellConfigScreen.create(minecraft.screen));
			}
			while (ModKeybinds.toggleAutoSell().consumeClick()) {
				manager.toggle(minecraft);
			}
			manager.tick(minecraft);
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> manager.onDisconnect());

		LOGGER.info("Macher Auto Sell initialized");
	}
}
