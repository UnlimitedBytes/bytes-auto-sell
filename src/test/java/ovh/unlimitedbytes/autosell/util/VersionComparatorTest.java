package ovh.unlimitedbytes.autosell.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionComparatorTest {

	@Test
	void tagVersusLocalFriendlyVersion() {
		assertTrue(VersionComparator.isNewer("v2.2.0", "2.1.0+mc1.21.11"));
		assertFalse(VersionComparator.isNewer("v2.1.0", "2.1.0+mc26.2"));
		assertFalse(VersionComparator.isNewer("v2.0.0", "2.1.0+mc26.2"));
	}

	@Test
	void numericPerComponentNotLexicographic() {
		assertTrue(VersionComparator.isNewer("v2.1.10", "2.1.9"));
		assertTrue(VersionComparator.isNewer("v3.0.0", "2.9.9"));
		assertTrue(VersionComparator.isNewer("v10.0.0", "9.0.0"));
	}

	@Test
	void missingComponentsCountAsZero() {
		assertTrue(VersionComparator.isNewer("v2", "1.9.9"));
		assertFalse(VersionComparator.isNewer("v2", "2.0.0"));
		assertTrue(VersionComparator.isNewer("v2.1", "2.0.9"));
	}

	@Test
	void buildMetadataAndPreReleaseSuffixesAreIgnored() {
		assertEquals("2.2.0", VersionComparator.normalize("v2.2.0"));
		assertEquals("2.1.0", VersionComparator.normalize("2.1.0+mc26.2"));
		assertEquals("2.2.0", VersionComparator.normalize("v2.2.0-rc1"));
		assertTrue(VersionComparator.isNewer("v2.2.0+build.5", "2.1.0+mc1.21.11"));
	}

	@Test
	void unparseableFailsClosed() {
		assertFalse(VersionComparator.isNewer("latest", "2.1.0+mc26.2"));
		assertFalse(VersionComparator.isNewer("", "2.1.0+mc26.2"));
		assertFalse(VersionComparator.isNewer(null, "2.1.0+mc26.2"));
		assertFalse(VersionComparator.isNewer("v2.2.0", null));
		assertFalse(VersionComparator.isNewer("v2.a.0", "2.1.0"));
		assertNull(VersionComparator.normalize("garbage"));
	}
}
