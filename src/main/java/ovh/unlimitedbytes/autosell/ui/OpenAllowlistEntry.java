package ovh.unlimitedbytes.autosell.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;

import java.util.List;
import java.util.Optional;

/**
 * A Cloth Config list entry that renders a full-width "Edit Allowlist…" button
 * and opens the {@link AllowlistScreen} when clicked. Carries no value of its
 * own: the allowlist is saved by the editor's Done button, and the surrounding
 * Cloth screen's Save/Cancel only governs the other settings.
 */
final class OpenAllowlistEntry extends AbstractConfigListEntry<Object> {
	private static final int COLOR_BORDER = 0xFF3C3C52;
	private static final int COLOR_FILL = 0xFF212126;
	private static final int COLOR_FILL_HOVER = 0xFF2E2E36;
	private static final int COLOR_LABEL = 0xFFF0F0F0;

	private final Runnable onPress;
	/**
	 * Whether the entry was hovered at its last render. Cloth's list offers
	 * clicks to every entry, visible or scrolled out, so this guards against a
	 * stale (scrolled-out) button rect claiming a click after a scroll — at most
	 * one frame old.
	 */
	private boolean renderedHovered;
	private int buttonX;
	private int buttonY;
	private int buttonWidth;
	private int buttonHeight;

	OpenAllowlistEntry(Component label, Runnable onPress) {
		super(label, false);
		this.onPress = onPress;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int index, int y, int x, int entryWidth,
			int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
		buttonX = x + 4;
		buttonY = y + 2;
		buttonWidth = Math.min(160, Math.max(0, entryWidth - 8));
		buttonHeight = Math.max(12, entryHeight - 4);
		renderedHovered = hovered;
		boolean inside = mouseX >= buttonX && mouseX < buttonX + buttonWidth
				&& mouseY >= buttonY && mouseY < buttonY + buttonHeight;
		context.fill(buttonX, buttonY, buttonX + buttonWidth, buttonY + buttonHeight,
				inside ? COLOR_FILL_HOVER : COLOR_FILL);
		context.outline(buttonX, buttonY, buttonX + buttonWidth, buttonY + buttonHeight, COLOR_BORDER);
		context.centeredText(Minecraft.getInstance().font, getFieldName(),
				buttonX + buttonWidth / 2, buttonY + (buttonHeight - 8) / 2, COLOR_LABEL);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (renderedHovered && click.button() == 0 && click.x() >= buttonX && click.x() < buttonX + buttonWidth
				&& click.y() >= buttonY && click.y() < buttonY + buttonHeight) {
			onPress.run();
			return true;
		}
		return false;
	}

	@Override
	public boolean isRequiresRestart() {
		return false;
	}

	@Override
	public void setRequiresRestart(boolean requiresRestart) {
		// nothing to restart: this entry carries no value
	}

	@Override
	public Component getFieldName() {
		return Component.translatable("bytesautosell.config.edit_allowlist");
	}

	@Override
	public Optional<Object> getDefaultValue() {
		return Optional.empty();
	}

	@Override
	public Object getValue() {
		return null;
	}

	@Override
	public List<? extends net.minecraft.client.gui.narration.NarratableEntry> narratables() {
		return List.of();
	}

	@Override
	public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() {
		return List.of();
	}
}
