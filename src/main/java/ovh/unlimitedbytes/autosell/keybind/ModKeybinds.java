package ovh.unlimitedbytes.autosell.keybind;

import ovh.unlimitedbytes.autosell.BytesAutoSellClient;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * The two mod keybinds, registered into the vanilla Controls screen.
 * The category label resolves to the translation key
 * {@code key.category.bytes-auto-sell.main}.
 */
public final class ModKeybinds {
	private static final KeyBinding.Category CATEGORY =
			KeyBinding.Category.create(Identifier.of(BytesAutoSellClient.MOD_ID, "main"));

	private static KeyBinding openSettings;
	private static KeyBinding toggleAutoSell;

	private ModKeybinds() {
	}

	public static void register() {
		openSettings = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.bytesautosell.open_settings",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_O,
				CATEGORY));
		toggleAutoSell = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.bytesautosell.toggle_autosell",
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
