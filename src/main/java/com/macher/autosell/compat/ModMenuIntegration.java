package com.macher.autosell.compat;

import com.macher.autosell.ui.AutoSellConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Loaded by Mod Menu only, via the {@code "modmenu"} entrypoint in fabric.mod.json.
 * Mod Menu is a soft dependency: without it, this class is never loaded.
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return AutoSellConfigScreen::create;
	}
}
