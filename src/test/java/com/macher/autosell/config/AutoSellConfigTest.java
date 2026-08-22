package com.macher.autosell.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoSellConfigTest {

	private static final Gson GSON = new Gson();

	/** Parses JSON the same way {@link AutoSellConfig#load()} does, bypassing the clamping setters. */
	private static AutoSellConfig fromJson(String json) {
		return GSON.fromJson(json, AutoSellConfig.class);
	}

	@Test
	void defaultsMatchSpecification() {
		AutoSellConfig config = new AutoSellConfig();
		assertEquals("/sell", config.getSellCommand());
		assertEquals(TransferMethod.SHIFT, config.getTransferMethod());
		assertEquals(SellMode.CLOSE_GUI, config.getSellMode());
		assertEquals(1, config.getTransferDelayTicks());
		assertEquals(1, config.getTransferBurst());
		assertEquals(false, config.isRandomizeTransferDelay());
		assertEquals(60, config.getReopenDelayTicks());
		assertEquals(false, config.isGuiTitleCheckEnabled());
		assertEquals("", config.getExpectedGuiTitle());
		assertEquals(35, config.getKeepOpenButtonSlot());
	}

	@Test
	void sanitizeClampsOutOfRangeValuesFromDisk() {
		AutoSellConfig config = new AutoSellConfig();
		config.copyFrom(fromJson(
				"{\"transferDelayTicks\":999,\"transferBurst\":0,\"reopenDelayTicks\":-10,\"keepOpenButtonSlot\":999}"));
		config.sanitize();
		assertEquals(AutoSellConfig.MAX_TRANSFER_DELAY_TICKS, config.getTransferDelayTicks());
		assertEquals(AutoSellConfig.MIN_TRANSFER_BURST, config.getTransferBurst());
		assertEquals(AutoSellConfig.MIN_REOPEN_DELAY_TICKS, config.getReopenDelayTicks());
		assertEquals(AutoSellConfig.MAX_BUTTON_SLOT, config.getKeepOpenButtonSlot());
	}

	@Test
	void sanitizeRestoresDefaultsForCorruptFields() {
		AutoSellConfig config = new AutoSellConfig();
		config.copyFrom(fromJson(
				"{\"sellCommand\":null,\"transferMethod\":null,\"sellMode\":null,\"expectedGuiTitle\":null}"));
		config.sanitize();
		assertEquals("/sell", config.getSellCommand());
		assertEquals(TransferMethod.SHIFT, config.getTransferMethod());
		assertEquals(SellMode.CLOSE_GUI, config.getSellMode());
		assertEquals("", config.getExpectedGuiTitle());
	}

	@Test
	void settersClampImmediately() {
		AutoSellConfig config = new AutoSellConfig();
		config.setTransferDelayTicks(-100);
		assertEquals(AutoSellConfig.MIN_TRANSFER_DELAY_TICKS, config.getTransferDelayTicks());
		config.setTransferBurst(1000);
		assertEquals(AutoSellConfig.MAX_TRANSFER_BURST, config.getTransferBurst());
		config.setSellCommand(null);
		assertEquals("/sell", config.getSellCommand());
		config.setExpectedGuiTitle(null);
		assertEquals("", config.getExpectedGuiTitle());
	}

	@Test
	void textValuesAreLengthLimited() {
		AutoSellConfig config = new AutoSellConfig();
		config.setSellCommand("/" + "a".repeat(10_000));
		assertEquals(AutoSellConfig.MAX_TEXT_LENGTH, config.getSellCommand().length());
		config.setExpectedGuiTitle("t".repeat(10_000));
		assertEquals(AutoSellConfig.MAX_TEXT_LENGTH, config.getExpectedGuiTitle().length());

		// sanitize also truncates raw values that bypassed the setters (e.g. from JSON)
		AutoSellConfig raw = new AutoSellConfig();
		raw.copyFrom(config);
		config.setExpectedGuiTitle("");
		config.setExpectedGuiTitle("t".repeat(10_000) + "overflow");
		config.sanitize();
		assertEquals(AutoSellConfig.MAX_TEXT_LENGTH, config.getExpectedGuiTitle().length());
	}
}
