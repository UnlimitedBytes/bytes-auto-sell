package ovh.unlimitedbytes.autosell.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandUtilTest {

	@Test
	void stripsLeadingSlash() {
		assertEquals("sell", CommandUtil.normalize("/sell"));
		assertEquals("sell", CommandUtil.normalize("sell"));
		assertEquals("sell", CommandUtil.normalize("//sell"));
	}

	@Test
	void trimsWhitespace() {
		assertEquals("sell all", CommandUtil.normalize("  /sell all  "));
		assertEquals("", CommandUtil.normalize("   "));
	}

	@Test
	void nullAndEmptyNormalizeToEmpty() {
		assertEquals("", CommandUtil.normalize(null));
		assertEquals("", CommandUtil.normalize(""));
		assertEquals("", CommandUtil.normalize("/"));
		assertEquals("", CommandUtil.normalize("//"));
	}

	@Test
	void keepsArgumentsAndInnerSlashes() {
		assertEquals("sell hand stone", CommandUtil.normalize("/sell hand stone"));
		assertEquals("warp a/b", CommandUtil.normalize("/warp a/b"));
	}
}
