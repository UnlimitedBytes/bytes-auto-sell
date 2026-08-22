package com.macher.autosell.config;

/**
 * How item stacks are moved from the player inventory into the sell GUI:
 * <ul>
 *   <li>{@link #SHIFT} uses quick-move (shift-click) on inventory slots.</li>
 *   <li>{@link #PICKUP} picks a stack up with the cursor and places it into an empty
 *       container slot.</li>
 * </ul>
 */
public enum TransferMethod {
	SHIFT("macherautosell.transfer_method.shift"),
	PICKUP("macherautosell.transfer_method.pickup");

	private final String translationKey;

	TransferMethod(String translationKey) {
		this.translationKey = translationKey;
	}

	public String translationKey() {
		return translationKey;
	}
}
