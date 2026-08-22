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

	public static KeyBinding OPEN_SETTINGS;
	public static KeyBinding TOGGLE_AUTO_SELL;

	private ModKeybinds() {
	}

	public static void register() {
		OPEN_SETTINGS = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.macherautosell.open_settings",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_O,
				CATEGORY));
		TOGGLE_AUTO_SELL = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.macherautosell.toggle_autosell",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				CATEGORY));
	}
}
