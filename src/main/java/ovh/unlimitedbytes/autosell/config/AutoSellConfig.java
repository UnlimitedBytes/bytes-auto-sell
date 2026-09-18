package ovh.unlimitedbytes.autosell.config;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client configuration, persisted as JSON in the config directory.
 * Every numeric value is clamped to its valid range before use, both after loading
 * and after every mutation, so the rest of the mod can rely on sane values.
 */
public final class AutoSellConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("BytesAutoSellConfig");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final String DEFAULT_SELL_COMMAND = "/sell";
	public static final SellMode DEFAULT_SELL_MODE = SellMode.KEEP_OPEN;
	/** Update check default; see {@link ovh.unlimitedbytes.autosell.update.UpdateChecker}. */
	public static final boolean DEFAULT_UPDATE_CHECK = true;
	/** Default "Use Allowlist" state: restricted selling is on out of the box. */
	public static final boolean DEFAULT_USE_ALLOWLIST = true;
	/** The only items sold out of the box. */
	public static final List<String> DEFAULT_ALLOWLIST = List.of(
			"minecraft:dandelion",
			"minecraft:poppy",
			"minecraft:pumpkin",
			"minecraft:melon_seeds",
			"minecraft:melon_slice");
	public static final int MAX_ALLOWLIST_ENTRIES = 1024;
	public static final int DEFAULT_TRANSFER_DELAY_TICKS = 1;
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
	private SellMode sellMode = DEFAULT_SELL_MODE;
	private int transferDelayTicks = DEFAULT_TRANSFER_DELAY_TICKS;
	private int transferBurst = DEFAULT_TRANSFER_BURST;
	private boolean randomizeTransferDelay = false;
	/** Delay between sell cycles: GUI reopen (Close GUI mode) or next batch (Keep Open mode). */
	private int reopenDelayTicks = DEFAULT_REOPEN_DELAY_TICKS;
	private boolean guiTitleCheckEnabled = false;
	private String expectedGuiTitle = "";
	/** Sell-button slot for Keep Open mode; clamped into the GUI's container region at use. */
	private int keepOpenButtonSlot = DEFAULT_BUTTON_SLOT;
	/** Whether the GitHub update check runs on server join. */
	private boolean updateCheckEnabled = DEFAULT_UPDATE_CHECK;
	/** Whether only allowlisted items are sold (see {@link #allowlist}). */
	private boolean useAllowlist = DEFAULT_USE_ALLOWLIST;
	/** Item ids (e.g. "minecraft:poppy") the mod may sell when the allowlist is enabled. */
	private List<String> allowlist = new ArrayList<>(DEFAULT_ALLOWLIST);
	/** Fast membership index over {@link #allowlist}; rebuilt whenever the list changes. */
	private Set<String> allowListIndex = new HashSet<>(DEFAULT_ALLOWLIST);

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
		return FabricLoader.getInstance().getConfigDir().resolve("bytes-auto-sell.json");
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
		this.updateCheckEnabled = other.updateCheckEnabled;
		this.useAllowlist = other.useAllowlist;
		this.allowlist = other.allowlist == null
				? new ArrayList<>(DEFAULT_ALLOWLIST)
				: new ArrayList<>(other.allowlist);
		rebuildAllowListIndex();
	}

	/**
	 * Clamps every value into its valid range. Null or blank string/enum fields
	 * restore their defaults; numeric fields absent from the JSON deserialize as 0
	 * and clamp to the range minimum.
	 */
	public void sanitize() {
		if (sellCommand == null || sellCommand.isBlank()) {
			sellCommand = DEFAULT_SELL_COMMAND;
		}
		if (transferMethod == null) {
			transferMethod = TransferMethod.SHIFT;
		}
		if (sellMode == null) {
			sellMode = DEFAULT_SELL_MODE;
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
		if (allowlist == null) {
			allowlist = new ArrayList<>(DEFAULT_ALLOWLIST);
		}
		List<String> cleaned = new ArrayList<>();
		for (String id : allowlist) {
			if (id == null) {
				continue;
			}
			String trimmed = truncate(id).trim();
			if (!trimmed.isEmpty() && !cleaned.contains(trimmed)) {
				cleaned.add(trimmed);
			}
			if (cleaned.size() >= MAX_ALLOWLIST_ENTRIES) {
				break;
			}
		}
		allowlist = cleaned;
		rebuildAllowListIndex();
	}

	private void rebuildAllowListIndex() {
		allowListIndex = new HashSet<>(allowlist);
	}

	/** Fast allowlist membership check used per inventory stack; see {@link #getAllowList()}. */
	public boolean isAllowListed(String itemId) {
		return allowListIndex.contains(itemId);
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
		this.sellMode = sellMode != null ? sellMode : DEFAULT_SELL_MODE;
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

	public boolean isUpdateCheckEnabled() {
		return updateCheckEnabled;
	}

	public void setUpdateCheckEnabled(boolean updateCheckEnabled) {
		this.updateCheckEnabled = updateCheckEnabled;
	}

	public boolean isUseAllowlist() {
		return useAllowlist;
	}

	public void setUseAllowlist(boolean useAllowlist) {
		this.useAllowlist = useAllowlist;
	}

	/** Unmodifiable snapshot; mutate via {@link #setAllowList(List)}. */
	public List<String> getAllowList() {
		return List.copyOf(allowlist);
	}

	public void setAllowList(List<String> allowlist) {
		this.allowlist = allowlist != null ? new ArrayList<>(allowlist) : new ArrayList<>(DEFAULT_ALLOWLIST);
		sanitize();
	}}
