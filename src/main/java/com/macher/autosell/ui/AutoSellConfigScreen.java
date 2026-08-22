package com.macher.autosell.ui;

import com.macher.autosell.config.AutoSellConfig;
import com.macher.autosell.config.SellMode;
import com.macher.autosell.config.TransferMethod;
import com.macher.autosell.sell.AutoSellManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * In-game settings screen, also exposed through Mod Menu.
 * Widget values write through to the live config immediately. Done persists to disk
 * and refreshes the snapshot; Cancel and Esc revert the live config to the snapshot
 * (the state at open, or the last saved state).
 */
public class AutoSellConfigScreen extends Screen {

	private static final int WIDGET_WIDTH = 300;
	private static final int WIDGET_HEIGHT = 20;
	private static final int ROW_HEIGHT = 28;
	private static final int TEXT_FIELD_WIDTH = 220;
	private static final int LABELED_FIELD_EXTRA = 10;

	private enum Tab {
		SELLING("macherautosell.config.tab.selling"),
		TIMING("macherautosell.config.tab.timing"),
		GUI_CHECK("macherautosell.config.tab.guicheck");

		final String titleKey;

		Tab(String titleKey) {
			this.titleKey = titleKey;
		}
	}

	private record LabelRow(String key, int y) {
	}

	private final Screen parent;
	private final AutoSellConfig config = AutoSellConfig.get();
	private final AutoSellManager manager = AutoSellManager.getInstance();
	/** Values to restore on Cancel/Esc: the state at open, refreshed after each save. */
	private AutoSellConfig snapshot;

	private Tab tab = Tab.SELLING;
	/** Labels drawn above text fields; rebuilt on every {@link #init()}. */
	private final List<LabelRow> fieldLabels = new ArrayList<>();

	public AutoSellConfigScreen(Screen parent) {
		super(Text.translatable("macherautosell.config.title"));
		this.parent = parent;
		this.snapshot = config.copy();
	}

	@Override
	protected void init() {
		int cx = this.width / 2;

		// Adaptive vertical layout so every tab fits on small scaled resolutions.
		int contentHeight = tabContentHeight();
		int totalHeight = 20 + 12 + contentHeight + 12 + 20;
		int top = Math.max(8, (this.height - totalHeight) / 2);
		int tabsY = top;
		int contentY = top + 20 + 12;
		int actionsY = contentY + contentHeight + 12;

		fieldLabels.clear();
		Tab[] tabs = Tab.values();
		int tabsRowWidth = tabs.length * 104 - 4;
		for (int i = 0; i < tabs.length; i++) {
			Tab t = tabs[i];
			addDrawableChild(ButtonWidget.builder(Text.translatable(t.titleKey), button -> {
				if (tab != t) {
					tab = t;
					clearAndInit();
				}
			}).dimensions(cx - tabsRowWidth / 2 + i * 104, tabsY, 100, 20).build());
		}

		switch (tab) {
			case SELLING -> initSellingTab(cx, contentY);
			case TIMING -> initTimingTab(cx, contentY);
			case GUI_CHECK -> initGuiCheckTab(cx, contentY);
		}

		addDrawableChild(ButtonWidget.builder(Text.translatable("macherautosell.config.done"), button -> {
			config.save();
			snapshot = config.copy();
			close();
		}).dimensions(cx - 152, actionsY, 150, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("macherautosell.config.cancel"), button -> close())
				.dimensions(cx + 2, actionsY, 150, 20).build());
	}

	private int tabContentHeight() {
		return switch (tab) {
			case SELLING -> 5 * ROW_HEIGHT + LABELED_FIELD_EXTRA;
			case TIMING -> 4 * ROW_HEIGHT;
			case GUI_CHECK -> 2 * ROW_HEIGHT + LABELED_FIELD_EXTRA;
		};
	}

	private void initSellingTab(int cx, int y) {
		addDrawableChild(ButtonWidget.builder(onOffText("macherautosell.config.autosell", manager.isEnabled()), button -> {
			manager.toggle(this.client);
			button.setMessage(onOffText("macherautosell.config.autosell", manager.isEnabled()));
		}).dimensions(cx - WIDGET_WIDTH / 2, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
		y += ROW_HEIGHT;

		y = addLabeledTextField(cx, y, "macherautosell.config.sell_command",
				config.getSellCommand(), config::setSellCommand);

		addDrawableChild(CyclingButtonWidget.builder(
						(SellMode value) -> valueText("macherautosell.config.sell_mode", value), config.getSellMode())
				.values(SellMode.values())
				.build(cx - WIDGET_WIDTH / 2, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.empty(),
						(button, value) -> config.setSellMode(value)));
		y += ROW_HEIGHT;

		addDrawableChild(CyclingButtonWidget.builder(
						(TransferMethod value) -> valueText("macherautosell.config.transfer_method", value), config.getTransferMethod())
				.values(TransferMethod.values())
				.build(cx - WIDGET_WIDTH / 2, y, WIDGET_WIDTH, WIDGET_HEIGHT, Text.empty(),
						(button, value) -> config.setTransferMethod(value)));
		y += ROW_HEIGHT;

		addDrawableChild(new IntSliderWidget(cx - WIDGET_WIDTH / 2, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"macherautosell.config.button_slot", AutoSellConfig.MIN_BUTTON_SLOT, AutoSellConfig.MAX_BUTTON_SLOT, 1,
				config.getKeepOpenButtonSlot(), config::setKeepOpenButtonSlot));
	}

	private void initTimingTab(int cx, int y) {
		addDrawableChild(new IntSliderWidget(cx - WIDGET_WIDTH / 2, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"macherautosell.config.transfer_delay", AutoSellConfig.MIN_TRANSFER_DELAY_TICKS, AutoSellConfig.MAX_TRANSFER_DELAY_TICKS, 1,
				config.getTransferDelayTicks(), config::setTransferDelayTicks));
		y += ROW_HEIGHT;

		addDrawableChild(ButtonWidget.builder(onOffText("macherautosell.config.randomize", config.isRandomizeTransferDelay()), button -> {
			config.setRandomizeTransferDelay(!config.isRandomizeTransferDelay());
			button.setMessage(onOffText("macherautosell.config.randomize", config.isRandomizeTransferDelay()));
		}).dimensions(cx - WIDGET_WIDTH / 2, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
		y += ROW_HEIGHT;

		addDrawableChild(new IntSliderWidget(cx - WIDGET_WIDTH / 2, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"macherautosell.config.transfer_burst", AutoSellConfig.MIN_TRANSFER_BURST, AutoSellConfig.MAX_TRANSFER_BURST, 1,
				config.getTransferBurst(), config::setTransferBurst));
		y += ROW_HEIGHT;

		addDrawableChild(new IntSliderWidget(cx - WIDGET_WIDTH / 2, y, WIDGET_WIDTH, WIDGET_HEIGHT,
				"macherautosell.config.reopen_delay", AutoSellConfig.MIN_REOPEN_DELAY_TICKS, AutoSellConfig.MAX_REOPEN_DELAY_TICKS, 5,
				config.getReopenDelayTicks(), config::setReopenDelayTicks));
	}

	private void initGuiCheckTab(int cx, int y) {
		addDrawableChild(ButtonWidget.builder(onOffText("macherautosell.config.title_check", config.isGuiTitleCheckEnabled()), button -> {
			config.setGuiTitleCheckEnabled(!config.isGuiTitleCheckEnabled());
			button.setMessage(onOffText("macherautosell.config.title_check", config.isGuiTitleCheckEnabled()));
		}).dimensions(cx - WIDGET_WIDTH / 2, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
		y += ROW_HEIGHT;

		addLabeledTextField(cx, y, "macherautosell.config.expected_title",
				config.getExpectedGuiTitle(), config::setExpectedGuiTitle);
	}

	/** Adds a small label row plus the text field below it; returns the y after the block. */
	private int addLabeledTextField(int cx, int y, String labelKey, String initial, java.util.function.Consumer<String> setter) {
		fieldLabels.add(new LabelRow(labelKey, y));
		TextFieldWidget field = new TextFieldWidget(this.textRenderer, cx - TEXT_FIELD_WIDTH / 2,
				y + LABELED_FIELD_EXTRA, TEXT_FIELD_WIDTH, WIDGET_HEIGHT, Text.translatable(labelKey));
		field.setMaxLength(256);
		field.setText(initial);
		field.setChangedListener(setter);
		addDrawableChild(field);
		return y + ROW_HEIGHT + LABELED_FIELD_EXTRA;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int tabsY = this.height / 2 - (20 + 12 + tabContentHeight() + 12 + 20) / 2;
		if (tabsY >= 22) {
			context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, tabsY - 12, 0xFFFFFF);
		}
		for (LabelRow label : fieldLabels) {
			context.drawTextWithShadow(this.textRenderer, Text.translatable(label.key()),
					this.width / 2 - TEXT_FIELD_WIDTH / 2, label.y(), 0xA0A0A0);
		}
	}

	@Override
	public void close() {
		if (this.client != null) {
			config.copyFrom(snapshot); // revert unsaved changes (Done refreshed the snapshot after saving)
			this.client.setScreen(parent);
		}
	}

	private Text onOffText(String key, boolean value) {
		return Text.translatable(key, Text.translatable(value ? "macherautosell.on" : "macherautosell.off"));
	}

	private Text valueText(String key, Enum<?> value) {
		final String subKey = switch (value) {
			case SellMode mode -> mode.translationKey();
			case TransferMethod method -> method.translationKey();
			default -> throw new IllegalArgumentException("Unexpected cycling value: " + value);
		};
		return Text.translatable(key, Text.translatable(subKey));
	}

	/** Slider for an int config value with a translated, value-formatted message and step snapping. */
	private static class IntSliderWidget extends SliderWidget {
		private final String translationKey;
		private final int min;
		private final int max;
		private final int step;
		private final IntConsumer setter;

		IntSliderWidget(int x, int y, int width, int height, String translationKey, int min, int max, int step, int initial, IntConsumer setter) {
			super(x, y, width, height, Text.empty(), (clamp(initial, min, max) - min) / (double) (max - min));
			this.translationKey = translationKey;
			this.min = min;
			this.max = max;
			this.step = Math.max(1, step);
			this.setter = setter;
			updateMessage();
		}

		private int intValue() {
			int raw = (int) Math.round(min + (max - min) * this.value);
			int stepped = min + (int) Math.round((raw - min) / (double) step) * step;
			return clamp(stepped, min, max);
		}

		@Override
		protected void updateMessage() {
			setMessage(Text.translatable(translationKey, intValue()));
		}

		@Override
		protected void applyValue() {
			setter.accept(intValue());
		}

		private static int clamp(int value, int min, int max) {
			return Math.max(min, Math.min(max, value));
		}
	}
}
