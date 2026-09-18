package ovh.unlimitedbytes.autosell.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoSellConfigAllowlistTest {

	@Test
	void freshDefaultsAreTheFiveStarterItems() {
		AutoSellConfig config = new AutoSellConfig();
		config.sanitize();
		assertTrue(config.isUseAllowlist());
		assertEquals(List.of(
				"minecraft:dandelion",
				"minecraft:poppy",
				"minecraft:pumpkin",
				"minecraft:melon_seeds",
				"minecraft:melon_slice"), config.getAllowList());
	}

	@Test
	void legacyConfigWithoutAllowlistFieldsKeepsDefaults() {
		// a 2.2.2 config file has no allowlist keys at all
		String json = "{\"sellCommand\":\"/sell\",\"transferBurst\":10}";
		AutoSellConfig read = new Gson().fromJson(json, AutoSellConfig.class);
		AutoSellConfig config = new AutoSellConfig();
		config.copyFrom(read);
		config.sanitize();
		assertTrue(config.isUseAllowlist());
		assertEquals(new AutoSellConfig().getAllowList(), config.getAllowList());
		assertEquals(10, config.getTransferBurst()); // legacy values survive
	}

	@Test
	void sanitizeDedupesTrimsAndDropsBlanks() {
		AutoSellConfig config = new AutoSellConfig();
		config.setAllowList(java.util.Arrays.asList(" minecraft:poppy ", "", "minecraft:poppy", null, "  "));
		assertEquals(List.of("minecraft:poppy"), config.getAllowList());
	}

	@Test
	void allowListIsCappedAtMaxEntries() {
		AutoSellConfig config = new AutoSellConfig();
		String[] huge = new String[AutoSellConfig.MAX_ALLOWLIST_ENTRIES + 50];
		for (int i = 0; i < huge.length; i++) {
			huge[i] = "minecraft:item_" + i;
		}
		config.setAllowList(List.of(huge));
		assertEquals(AutoSellConfig.MAX_ALLOWLIST_ENTRIES, config.getAllowList().size());
	}

	@Test
	void indexReflectsAllowlistMembership() {
		AutoSellConfig config = new AutoSellConfig();
		config.setAllowList(List.of("minecraft:poppy"));
		assertTrue(config.isAllowListed("minecraft:poppy"));
		assertFalse(config.isAllowListed("minecraft:diamond"));
		config.setAllowList(List.of());
		assertFalse(config.isAllowListed("minecraft:poppy"));
	}

	@Test
	void indexIsNotSerialized() {
		AutoSellConfig config = new AutoSellConfig();
		config.setAllowList(List.of("minecraft:poppy"));
		String json = new Gson().toJson(config);
		assertFalse(json.contains("allowListIndex"), "derived index must never be written to the config file");
		assertTrue(json.contains("\"allowlist\""));
	}

	@Test
	void nullSetAllowListFallsBackToDefaults() {
		AutoSellConfig config = new AutoSellConfig();
		config.setAllowList(null);
		assertEquals(new AutoSellConfig().getAllowList(), config.getAllowList());
	}
}
