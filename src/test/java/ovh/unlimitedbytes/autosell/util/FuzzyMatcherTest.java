package ovh.unlimitedbytes.autosell.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuzzyMatcherTest {

	@Test
	void blankQueryMatchesEverything() {
		assertTrue(FuzzyMatcher.isMatch("", "anything"));
		assertTrue(FuzzyMatcher.isMatch("   ", "anything"));
		assertEquals(0, FuzzyMatcher.scoreTokens("", "anything"));
	}

	@Test
	void subsequenceMatchingAcrossWordSeparators() {
		assertTrue(FuzzyMatcher.isMatch("drk", "dark_dye"));
		assertTrue(FuzzyMatcher.isMatch("melon sed", "melon_seeds"));
		assertTrue(FuzzyMatcher.isMatch("mseed", "minecraft:melon_seeds"));
		assertTrue(FuzzyMatcher.isMatch("sed melon", "melon_seeds")); // token order is irrelevant
	}

	@Test
	void caseInsensitive() {
		assertTrue(FuzzyMatcher.isMatch("DANDELION", "minecraft:dandelion"));
		assertTrue(FuzzyMatcher.isMatch("Poppy", "minecraft:poppy"));
	}

	@Test
	void nonMatchReturnsMinusOne() {
		assertEquals(-1, FuzzyMatcher.scoreTokens("xyz", "minecraft:dandelion"));
		assertEquals(-1, FuzzyMatcher.scoreTokens("poppy", "minecraft:dandelion"));
		assertFalse(FuzzyMatcher.isMatch("netherite", "dandelion"));
	}

	@Test
	void wordStartAndContiguityRankHigher() {
		int wordStart = FuzzyMatcher.scoreTokens("dand", "minecraft:dandelion");
		int scattered = FuzzyMatcher.scoreTokens("dnd", "minecraft:dead_bush_and_dnd");
		int contiguous = FuzzyMatcher.scoreTokens("dande", "minecraft:dandelion");
		int scatteredSameTarget = FuzzyMatcher.scoreTokens("dndn", "minecraft:dandelion");
		assertTrue(wordStart > scatteredSameTarget, "word-start hit should outrank scattered hits");
		assertTrue(contiguous > scatteredSameTarget, "contiguous hit should outrank scattered hits");
		assertTrue(scattered >= 0);
	}

	@Test
	void shorterTargetsScoreHigherOnEqualMatch() {
		int shortTarget = FuzzyMatcher.scoreTokens("poppy", "poppy");
		int longTarget = FuzzyMatcher.scoreTokens("poppy", "minecraft:very_long_name_with_poppy_inside");
		assertTrue(shortTarget > longTarget);
	}

	@Test
	void nullSafety() {
		assertEquals(-1, FuzzyMatcher.scoreTokens(null, "target"));
		assertEquals(-1, FuzzyMatcher.scoreTokens("query", null));
		assertEquals(-1, FuzzyMatcher.scoreTokens("", null)); // a null target never matches, even a blank query
		assertFalse(FuzzyMatcher.isMatch(null, "target"));
	}
}
