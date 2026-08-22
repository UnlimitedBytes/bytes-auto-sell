package com.macher.autosell.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleMatcherTest {

	@Test
	void disabledCheckAcceptsEverything() {
		assertTrue(TitleMatcher.matches(false, "", "anything"));
		assertTrue(TitleMatcher.matches(false, null, null));
	}

	@Test
	void enabledCheckRequiresExactTitle() {
		assertTrue(TitleMatcher.matches(true, "Sell GUI", "Sell GUI"));
		assertTrue(TitleMatcher.matches(true, "  Sell GUI  ", "Sell GUI"));
		assertFalse(TitleMatcher.matches(true, "Sell GUI", "Shop"));
		assertFalse(TitleMatcher.matches(true, "Sell GUI", "Sell GUI Extra"));
	}

	@Test
	void enabledCheckWithBlankExpectedTitleMatchesNothing() {
		// A blank expected title must never widen into a match-everything check.
		assertFalse(TitleMatcher.matches(true, "", "Sell GUI"));
		assertFalse(TitleMatcher.matches(true, null, "Sell GUI"));
		assertFalse(TitleMatcher.matches(true, "   ", "Sell GUI"));
	}

	@Test
	void nullActualTitleNeverMatches() {
		assertFalse(TitleMatcher.matches(true, "Sell GUI", null));
	}
}
