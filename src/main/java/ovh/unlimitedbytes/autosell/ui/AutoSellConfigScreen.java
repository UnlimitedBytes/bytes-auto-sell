package ovh.unlimitedbytes.autosell.ui;

import ovh.unlimitedbytes.autosell.config.AutoSellConfig;
import ovh.unlimitedbytes.autosell.config.SellMode;
import ovh.unlimitedbytes.autosell.config.TransferMethod;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Builds the settings screen with Cloth Config (bundled via jar-in-jar).
 * Edits are applied to the live config only when the screen is saved; Cancel
 * and Esc-with-changes discard them (Cloth confirms unsaved changes first).
 * The Auto Sell toggle itself stays on its keybind: it is runtime state, and
 * saving a config screen must never silently re-enable after a safety stop.
 */
public final class AutoSellConfigScreen {
	private AutoSellConfigScreen() {
	}

	public static Screen create(Screen parent) {
		AutoSellConfig config = AutoSellConfig.get();

		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Text.translatable("bytesautosell.config.title"));
		builder.setDoesConfirmSave(true);
		builder.setSavingRunnable(config::save);

		ConfigEntryBuilder entries = builder.entryBuilder();

		ConfigCategory selling = builder.getOrCreateCategory(Text.translatable("bytesautosell.config.tab.selling"));
		selling.addEntry(entries.startStrField(Text.translatable("bytesautosell.config.sell_command"), config.getSellCommand())
				.setDefaultValue(AutoSellConfig.DEFAULT_SELL_COMMAND)
				.setTooltip(Text.translatable("bytesautosell.config.sell_command.tooltip"))
				.setSaveConsumer(config::setSellCommand)
				.build());
		selling.addEntry(entries.startEnumSelector(Text.translatable("bytesautosell.config.sell_mode"), SellMode.class, config.getSellMode())
				.setDefaultValue(SellMode.CLOSE_GUI)
				.setEnumNameProvider(mode -> Text.translatable(((SellMode) mode).translationKey()))
				.setTooltip(Text.translatable("bytesautosell.config.sell_mode.tooltip"))
				.setSaveConsumer(config::setSellMode)
				.build());
		selling.addEntry(entries.startEnumSelector(Text.translatable("bytesautosell.config.transfer_method"), TransferMethod.class, config.getTransferMethod())
				.setDefaultValue(TransferMethod.SHIFT)
				.setEnumNameProvider(method -> Text.translatable(((TransferMethod) method).translationKey()))
				.setTooltip(Text.translatable("bytesautosell.config.transfer_method.tooltip"))
				.setSaveConsumer(config::setTransferMethod)
				.build());
		selling.addEntry(entries.startIntSlider(Text.translatable("bytesautosell.config.button_slot"), config.getKeepOpenButtonSlot(),
						AutoSellConfig.MIN_BUTTON_SLOT, AutoSellConfig.MAX_BUTTON_SLOT)
				.setDefaultValue(AutoSellConfig.DEFAULT_BUTTON_SLOT)
				.setTooltip(Text.translatable("bytesautosell.config.button_slot.tooltip"))
				.setSaveConsumer(config::setKeepOpenButtonSlot)
				.build());

		ConfigCategory timing = builder.getOrCreateCategory(Text.translatable("bytesautosell.config.tab.timing"));
		timing.addEntry(entries.startIntSlider(Text.translatable("bytesautosell.config.transfer_delay"), config.getTransferDelayTicks(),
						AutoSellConfig.MIN_TRANSFER_DELAY_TICKS, AutoSellConfig.MAX_TRANSFER_DELAY_TICKS)
				.setDefaultValue(AutoSellConfig.DEFAULT_TRANSFER_DELAY_TICKS)
				.setTooltip(Text.translatable("bytesautosell.config.transfer_delay.tooltip"))
				.setSaveConsumer(config::setTransferDelayTicks)
				.build());
		timing.addEntry(entries.startIntSlider(Text.translatable("bytesautosell.config.transfer_burst"), config.getTransferBurst(),
						AutoSellConfig.MIN_TRANSFER_BURST, AutoSellConfig.MAX_TRANSFER_BURST)
				.setDefaultValue(AutoSellConfig.DEFAULT_TRANSFER_BURST)
				.setTooltip(Text.translatable("bytesautosell.config.transfer_burst.tooltip"))
				.setSaveConsumer(config::setTransferBurst)
				.build());
		timing.addEntry(entries.startBooleanToggle(Text.translatable("bytesautosell.config.randomize"), config.isRandomizeTransferDelay())
				.setDefaultValue(false)
				.setYesNoTextSupplier(value -> Text.translatable(value ? "bytesautosell.on" : "bytesautosell.off"))
				.setTooltip(Text.translatable("bytesautosell.config.randomize.tooltip"))
				.setSaveConsumer(config::setRandomizeTransferDelay)
				.build());
		timing.addEntry(entries.startIntSlider(Text.translatable("bytesautosell.config.reopen_delay"), config.getReopenDelayTicks(),
						AutoSellConfig.MIN_REOPEN_DELAY_TICKS, AutoSellConfig.MAX_REOPEN_DELAY_TICKS)
				.setDefaultValue(AutoSellConfig.DEFAULT_REOPEN_DELAY_TICKS)
				.setTooltip(Text.translatable("bytesautosell.config.reopen_delay.tooltip"))
				.setSaveConsumer(config::setReopenDelayTicks)
				.build());

		ConfigCategory guiCheck = builder.getOrCreateCategory(Text.translatable("bytesautosell.config.tab.guicheck"));
		guiCheck.addEntry(entries.startBooleanToggle(Text.translatable("bytesautosell.config.title_check"), config.isGuiTitleCheckEnabled())
				.setDefaultValue(false)
				.setYesNoTextSupplier(value -> Text.translatable(value ? "bytesautosell.on" : "bytesautosell.off"))
				.setTooltip(Text.translatable("bytesautosell.config.title_check.tooltip"))
				.setSaveConsumer(config::setGuiTitleCheckEnabled)
				.build());
		guiCheck.addEntry(entries.startStrField(Text.translatable("bytesautosell.config.expected_title"), config.getExpectedGuiTitle())
				.setDefaultValue("")
				.setTooltip(Text.translatable("bytesautosell.config.expected_title.tooltip"))
				.setSaveConsumer(config::setExpectedGuiTitle)
				.build());

		return builder.build();
	}
}
