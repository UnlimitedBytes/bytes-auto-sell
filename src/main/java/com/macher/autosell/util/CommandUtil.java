package com.macher.autosell.util;

/**
 * Normalizes a user-configured command into the form expected by
 * {@code ClientPlayNetworkHandler#sendCommand} (no leading slash).
 */
public final class CommandUtil {
	private CommandUtil() {
	}

	/**
	 * Strips surrounding whitespace and any leading slashes.
	 *
	 * @return the command without slash, or {@code ""} if nothing usable remains
	 */
	public static String normalize(String raw) {
		if (raw == null) {
			return "";
		}
		String command = raw.trim();
		while (command.startsWith("/")) {
			command = command.substring(1);
		}
		return command.trim();
	}
}
