package com.macher.autosell.ui;

import com.macher.autosell.config.AutoSellConfig;
import com.macher.autosell.config.SellMode;
import com.macher.autosell.config.TransferMethod;
import com.macher.autosell.sell.AutoSellManager;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Builds the settings screen with Cloth Config (bundled via jar-in-jar).
 * Edits are applied to the live config only when the screen is saved;
 * Cancel discards them.
 */
public final class AutoSellConfigScreen {
	private AutoSellConfigScreen() {
	}

	public static Screen create(Screen parent) {
		AutoSellConfig config = AutoSellConfig.get();
		AutoSellManager manager = AutoSellManager.getInstance();

		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Text.translatable("macherautosell.config.title"));
		builder.setSavingRunnable(config::save);

		ConfigEntryBuilder entries = builder.entryBuilder();

		ConfigCategory selling = builder.getOrCreateCategory(Text.translatable("macherautosell.config.tab.selling"));
		selling.addEntry(entries.startBooleanToggle(Text.translatable("macherautosell.config.autosell"), manager.isEnabled())
				.setDefaultValue(false)
				.setYesNoTextSupplier(value -> Text.translatable(value ? "macherautosell.on" : "macherautosell.off"))
				.setSaveConsumer(value -> {
					if (value != manager.isEnabled()) {
						manager.toggle(MinecraftClient.getInstance());
					}
				})
				.build());
		selling.addEntry(entries.startStrField(Text.translatable("macherautosell.config.sell_command"), config.getSellCommand())
				.setDefaultValue(AutoSellConfig.DEFAULT_SELL_COMMAND)
				.setTooltip(Text.translatable("macherautosell.config.sell_command.tooltip"))
				.setSaveConsumer(config::setSellCommand)
				.build());
		selling.addEntry(entries.startEnumSelector(Text.translatable("macherautosell.config.sell_mode"), SellMode.class, config.getSellMode())
				.setDefaultValue(SellMode.CLOSE_GUI)
				.setEnumNameProvider(mode -> Text.translatable(((SellMode) mode).translationKey()))
				.setSaveConsumer(config::setSellMode)
				.build());
		selling.addEntry(entries.startEnumSelector(Text.translatable("macherautosell.config.transfer_method"), TransferMethod.class, config.getTransferMethod())
				.setDefaultValue(TransferMethod.SHIFT)
				.setEnumNameProvider(method -> Text.translatable(((TransferMethod) method).translationKey()))
				.setSaveConsumer(config::setTransferMethod)
				.build());
		selling.addEntry(entries.startIntSlider(Text.translatable("macherautosell.config.button_slot"), config.getKeepOpenButtonSlot(),
						AutoSellConfig.MIN_BUTTON_SLOT, AutoSellConfig.MAX_BUTTON_SLOT)
				.setDefaultValue(AutoSellConfig.DEFAULT_BUTTON_SLOT)
				.setSaveConsumer(config::setKeepOpenButtonSlot)
				.build());

		ConfigCategory timing = builder.getOrCreateCategory(Text.translatable("macherautosell.config.tab.timing"));
		timing.addEntry(entries.startIntSlider(Text.translatable("macherautosell.config.transfer_delay"), config.getTransferDelayTicks(),
						AutoSellConfig.MIN_TRANSFER_DELAY_TICKS, AutoSellConfig.MAX_TRANSFER_DELAY_TICKS)
				.setDefaultValue(AutoSellConfig.DEFAULT_TRANSFER_DELAY_TICKS)
				.setSaveConsumer(config::setTransferDelayTicks)
				.build());
		timing.addEntry(entries.startIntSlider(Text.translatable("macherautosell.config.transfer_burst"), config.getTransferBurst(),
						AutoSellConfig.MIN_TRANSFER_BURST, AutoSellConfig.MAX_TRANSFER_BURST)
				.setDefaultValue(AutoSellConfig.DEFAULT_TRANSFER_BURST)
				.setSaveConsumer(config::setTransferBurst)
				.build());
		timing.addEntry(entries.startBooleanToggle(Text.translatable("macherautosell.config.randomize"), config.isRandomizeTransferDelay())
				.setDefaultValue(false)
				.setYesNoTextSupplier(value -> Text.translatable(value ? "macherautosell.on" : "macherautosell.off"))
				.setSaveConsumer(config::setRandomizeTransferDelay)
				.build());
		timing.addEntry(entries.startIntSlider(Text.translatable("macherautosell.config.reopen_delay"), config.getReopenDelayTicks(),
						AutoSellConfig.MIN_REOPEN_DELAY_TICKS, AutoSellConfig.MAX_REOPEN_DELAY_TICKS)
				.setDefaultValue(AutoSellConfig.DEFAULT_REOPEN_DELAY_TICKS)
				.setSaveConsumer(config::setReopenDelayTicks)
				.build());

		ConfigCategory guiCheck = builder.getOrCreateCategory(Text.translatable("macherautosell.config.tab.guicheck"));
		guiCheck.addEntry(entries.startBooleanToggle(Text.translatable("macherautosell.config.title_check"), config.isGuiTitleCheckEnabled())
				.setDefaultValue(false)
				.setYesNoTextSupplier(value -> Text.translatable(value ? "macherautosell.on" : "macherautosell.off"))
				.setSaveConsumer(config::setGuiTitleCheckEnabled)
				.build());
		guiCheck.addEntry(entries.startStrField(Text.translatable("macherautosell.config.expected_title"), config.getExpectedGuiTitle())
				.setDefaultValue("")
				.setTooltip(Text.translatable("macherautosell.config.expected_title.tooltip"))
				.setSaveConsumer(config::setExpectedGuiTitle)
				.build());

		return builder.build();
	}
}
