package com.macher.autosell.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Client configuration, persisted as JSON in the config directory.
 * Every numeric value is clamped to its valid range before use, both after loading
 * and after every mutation, so the rest of the mod can rely on sane values.
 */
public final class AutoSellConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("MacherAutoSellConfig");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final String DEFAULT_SELL_COMMAND = "/sell";
	public static final int DEFAULT_TRANSFER_DELAY_TICKS = 20;
	public static final int DEFAULT_TRANSFER_BURST = 10;
	public static final int DEFAULT_REOPEN_DELAY_TICKS = 20;
	public static final int DEFAULT_BUTTON_SLOT = 35;

	public static final int MIN_TRANSFER_DELAY_TICKS = 0;
	public static final int MAX_TRANSFER_DELAY_TICKS = 20;
	public static final int MIN_TRANSFER_BURST = 1;
	public static final int MAX_TRANSFER_BURST = 10;
	public static final int MIN_REOPEN_DELAY_TICKS = 5;
	public static final int MAX_REOPEN_DELAY_TICKS = 1200;
	public static final int MIN_BUTTON_SLOT = 0;
	public static final int MAX_BUTTON_SLOT = 53;
	/** Cloth Config text fields are effectively unbounded, so the config clamps itself. */
	public static final int MAX_TEXT_LENGTH = 256;

	private static AutoSellConfig instance = new AutoSellConfig();

	private String sellCommand = DEFAULT_SELL_COMMAND;
	private TransferMethod transferMethod = TransferMethod.SHIFT;
	private SellMode sellMode = SellMode.CLOSE_GUI;
	private int transferDelayTicks = DEFAULT_TRANSFER_DELAY_TICKS;
	private int transferBurst = DEFAULT_TRANSFER_BURST;
	private boolean randomizeTransferDelay = false;
	/** Delay between sell cycles: GUI reopen (Close GUI mode) or next batch (Keep Open mode). */
	private int reopenDelayTicks = DEFAULT_REOPEN_DELAY_TICKS;
	private boolean guiTitleCheckEnabled = false;
	private String expectedGuiTitle = "";
	/** Sell-button slot for Keep Open mode; clamped into the GUI's container region at use. */
	private int keepOpenButtonSlot = DEFAULT_BUTTON_SLOT;

	public static AutoSellConfig get() {
		return instance;
	}

	/** Loads the config file, replacing the live values; falls back to defaults on any error. */
	public static void load() {
		Path path = configPath();
		if (Files.exists(path)) {
			try {
				AutoSellConfig read = GSON.fromJson(Files.readString(path), AutoSellConfig.class);
				if (read != null) {
					instance.copyFrom(read);
				}
			} catch (IOException | JsonParseException e) {
				LOGGER.error("Failed to read config {}; falling back to defaults", path, e);
			}
		}
		instance.sanitize();
	}

	public void save() {
		sanitize();
		Path path = configPath();
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(tmp, GSON.toJson(this));
			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOGGER.error("Failed to save config", e);
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException cleanupError) {
				LOGGER.warn("Failed to delete temporary config file {}", tmp, cleanupError);
			}
		}
	}

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("macher-auto-sell.json");
	}

	/** Copies all field values from {@code other} into this config, without sanitizing. */
	public void copyFrom(AutoSellConfig other) {
		this.sellCommand = other.sellCommand;
		this.transferMethod = other.transferMethod;
		this.sellMode = other.sellMode;
		this.transferDelayTicks = other.transferDelayTicks;
		this.transferBurst = other.transferBurst;
		this.randomizeTransferDelay = other.randomizeTransferDelay;
		this.reopenDelayTicks = other.reopenDelayTicks;
		this.guiTitleCheckEnabled = other.guiTitleCheckEnabled;
		this.expectedGuiTitle = other.expectedGuiTitle;
		this.keepOpenButtonSlot = other.keepOpenButtonSlot;
	}

	/** Clamps every value into its valid range and restores defaults for missing fields. */
	public void sanitize() {
		if (sellCommand == null || sellCommand.isBlank()) {
			sellCommand = DEFAULT_SELL_COMMAND;
		}
		if (transferMethod == null) {
			transferMethod = TransferMethod.SHIFT;
		}
		if (sellMode == null) {
			sellMode = SellMode.CLOSE_GUI;
		}
		if (expectedGuiTitle == null) {
			expectedGuiTitle = "";
		}
		sellCommand = truncate(sellCommand);
		expectedGuiTitle = truncate(expectedGuiTitle);
		transferDelayTicks = clamp(transferDelayTicks, MIN_TRANSFER_DELAY_TICKS, MAX_TRANSFER_DELAY_TICKS);
		transferBurst = clamp(transferBurst, MIN_TRANSFER_BURST, MAX_TRANSFER_BURST);
		reopenDelayTicks = clamp(reopenDelayTicks, MIN_REOPEN_DELAY_TICKS, MAX_REOPEN_DELAY_TICKS);
		keepOpenButtonSlot = clamp(keepOpenButtonSlot, MIN_BUTTON_SLOT, MAX_BUTTON_SLOT);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static String truncate(String value) {
		return value.length() <= MAX_TEXT_LENGTH ? value : value.substring(0, MAX_TEXT_LENGTH);
	}

	public String getSellCommand() {
		return sellCommand;
	}

	public void setSellCommand(String sellCommand) {
		this.sellCommand = sellCommand != null ? truncate(sellCommand) : DEFAULT_SELL_COMMAND;
	}

	public TransferMethod getTransferMethod() {
		return transferMethod;
	}

	public void setTransferMethod(TransferMethod transferMethod) {
		this.transferMethod = transferMethod != null ? transferMethod : TransferMethod.SHIFT;
	}

	public SellMode getSellMode() {
		return sellMode;
	}

	public void setSellMode(SellMode sellMode) {
		this.sellMode = sellMode != null ? sellMode : SellMode.CLOSE_GUI;
	}

	public int getTransferDelayTicks() {
		return transferDelayTicks;
	}

	public void setTransferDelayTicks(int transferDelayTicks) {
		this.transferDelayTicks = clamp(transferDelayTicks, MIN_TRANSFER_DELAY_TICKS, MAX_TRANSFER_DELAY_TICKS);
	}

	public int getTransferBurst() {
		return transferBurst;
	}

	public void setTransferBurst(int transferBurst) {
		this.transferBurst = clamp(transferBurst, MIN_TRANSFER_BURST, MAX_TRANSFER_BURST);
	}

	public boolean isRandomizeTransferDelay() {
		return randomizeTransferDelay;
	}

	public void setRandomizeTransferDelay(boolean randomizeTransferDelay) {
		this.randomizeTransferDelay = randomizeTransferDelay;
	}

	public int getReopenDelayTicks() {
		return reopenDelayTicks;
	}

	public void setReopenDelayTicks(int reopenDelayTicks) {
		this.reopenDelayTicks = clamp(reopenDelayTicks, MIN_REOPEN_DELAY_TICKS, MAX_REOPEN_DELAY_TICKS);
	}

	public boolean isGuiTitleCheckEnabled() {
		return guiTitleCheckEnabled;
	}

	public void setGuiTitleCheckEnabled(boolean guiTitleCheckEnabled) {
		this.guiTitleCheckEnabled = guiTitleCheckEnabled;
	}

	public String getExpectedGuiTitle() {
		return expectedGuiTitle;
	}

	public void setExpectedGuiTitle(String expectedGuiTitle) {
		this.expectedGuiTitle = expectedGuiTitle != null ? truncate(expectedGuiTitle) : "";
	}

	public int getKeepOpenButtonSlot() {
		return keepOpenButtonSlot;
	}

	public void setKeepOpenButtonSlot(int keepOpenButtonSlot) {
		this.keepOpenButtonSlot = clamp(keepOpenButtonSlot, MIN_BUTTON_SLOT, MAX_BUTTON_SLOT);
	}
}
