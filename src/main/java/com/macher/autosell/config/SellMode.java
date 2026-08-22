package com.macher.autosell.config;

/**
 * How a sell cycle is completed after the inventory has been deposited:
 * <ul>
 *   <li>{@link #CLOSE_GUI} closes the sell GUI (the server sells on close) and reopens
 *       it after the configured delay.</li>
 *   <li>{@link #KEEP_OPEN} keeps the GUI open and clicks the configured button slot to
 *       sell, then deposits the next batch after the configured delay.</li>
 * </ul>
 */
public enum SellMode {
	CLOSE_GUI("macherautosell.sell_mode.close_gui"),
	KEEP_OPEN("macherautosell.sell_mode.keep_open");

	private final String translationKey;

	SellMode(String translationKey) {
		this.translationKey = translationKey;
	}

	public String translationKey() {
		return translationKey;
	}
}
