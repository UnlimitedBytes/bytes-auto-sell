package ovh.unlimitedbytes.autosell.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
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
	private int buttonX;
	private int buttonY;
	private int buttonWidth;
	private int buttonHeight;

	OpenAllowlistEntry(Text label, Runnable onPress) {
		super(label, false);
		this.onPress = onPress;
	}

	@Override
	public void render(DrawContext context, int index, int x, int width, int y, int height,
			int mouseX, int mouseY, boolean hovered, float delta) {
		buttonX = x + 4;
		buttonY = y + 2;
		buttonWidth = Math.min(160, width - 8);
		buttonHeight = Math.max(12, height - 4);
		boolean inside = mouseX >= buttonX && mouseX < buttonX + buttonWidth
				&& mouseY >= buttonY && mouseY < buttonY + buttonHeight;
		context.fill(buttonX, buttonY, buttonX + buttonWidth, buttonY + buttonHeight,
				inside ? COLOR_FILL_HOVER : COLOR_FILL);
		context.fill(buttonX, buttonY, buttonX + buttonWidth, buttonY + 1, COLOR_BORDER);
		context.fill(buttonX, buttonY + buttonHeight - 1, buttonX + buttonWidth, buttonY + buttonHeight, COLOR_BORDER);
		context.fill(buttonX, buttonY, buttonX + 1, buttonY + buttonHeight, COLOR_BORDER);
		context.fill(buttonX + buttonWidth - 1, buttonY, buttonX + buttonWidth, buttonY + buttonHeight, COLOR_BORDER);
		context.drawCenteredTextWithShadow(textRenderer(), getFieldName(),
				buttonX + buttonWidth / 2, buttonY + (buttonHeight - 8) / 2, COLOR_LABEL);
	}

	private net.minecraft.client.font.TextRenderer textRenderer() {
		return MinecraftClient.getInstance().textRenderer;
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		if (click.button() == 0 && click.x() >= buttonX && click.x() < buttonX + buttonWidth
				&& click.y() >= buttonY && click.y() < buttonY + buttonHeight) {
			onPress.run();
			return true;
		}
		return false;
	}

	@Override
	public List<? extends net.minecraft.client.gui.Selectable> narratables() {
		return List.of();
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
	public Text getFieldName() {
		return Text.translatable("bytesautosell.config.edit_allowlist");
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
	public List<? extends net.minecraft.client.gui.Element> children() {
		return List.of();
	}
}
