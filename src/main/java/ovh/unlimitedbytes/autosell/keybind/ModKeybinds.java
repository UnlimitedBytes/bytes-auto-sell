package ovh.unlimitedbytes.autosell.keybind;

import ovh.unlimitedbytes.autosell.BytesAutoSellClient;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * The two mod keybinds, registered into the vanilla Controls screen.
 * The category label resolves to the translation key
 * {@code key.category.bytes-auto-sell.main}.
 */
public final class ModKeybinds {
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(BytesAutoSellClient.MOD_ID, "main"));

	private static KeyMapping openSettings;
	private static KeyMapping toggleAutoSell;

	private ModKeybinds() {
	}

	public static void register() {
		openSettings = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.bytesautosell.open_settings",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_O,
				CATEGORY));
		toggleAutoSell = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.bytesautosell.toggle_autosell",
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
