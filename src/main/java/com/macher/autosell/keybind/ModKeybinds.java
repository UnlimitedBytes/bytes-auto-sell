package com.macher.autosell.keybind;

import com.macher.autosell.MacherAutoSellClient;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * The two mod keybinds, registered into the vanilla Controls screen.
 * The category label resolves to the translation key
 * {@code key.category.macher-auto-sell.main}.
 */
public final class ModKeybinds {
	private static final KeyBinding.Category CATEGORY =
			KeyBinding.Category.create(Identifier.of(MacherAutoSellClient.MOD_ID, "main"));

	private static KeyBinding openSettings;
	private static KeyBinding toggleAutoSell;

	private ModKeybinds() {
	}

	public static void register() {
		openSettings = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.macherautosell.open_settings",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_O,
				CATEGORY));
		toggleAutoSell = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.macherautosell.toggle_autosell",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				CATEGORY));
	}

	public static KeyBinding openSettings() {
		return openSettings;
	}

	public static KeyBinding toggleAutoSell() {
		return toggleAutoSell;
	}
}
