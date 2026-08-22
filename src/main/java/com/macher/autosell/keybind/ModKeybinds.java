package com.macher.autosell.keybind;

import com.macher.autosell.MacherAutoSellClient;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * The two mod keybinds, registered into the vanilla Controls screen.
 * The category label resolves to the translation key
 * {@code key.category.macher-auto-sell.main}.
 */
public final class ModKeybinds {
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MacherAutoSellClient.MOD_ID, "main"));

	private static KeyMapping openSettings;
	private static KeyMapping toggleAutoSell;

	private ModKeybinds() {
	}

	public static void register() {
		openSettings = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.macherautosell.open_settings",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_O,
				CATEGORY));
		toggleAutoSell = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.macherautosell.toggle_autosell",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				CATEGORY));
	}

	public static KeyMapping openSettings() {
		return openSettings;
	}

	public static KeyMapping toggleAutoSell() {
		return toggleAutoSell;
	}
}
