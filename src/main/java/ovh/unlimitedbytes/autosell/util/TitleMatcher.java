package ovh.unlimitedbytes.autosell.util;

/**
 * Decides whether an open screen is the sell GUI, based on the optional title check.
 * When the check is disabled, every generic container screen is accepted.
 * When it is enabled, a blank expected title matches nothing — never everything —
 * so a misconfiguration can never widen the match.
 */
public final class TitleMatcher {
	private TitleMatcher() {
	}

	public static boolean matches(boolean checkEnabled, String expectedTitle, String actualTitle) {
		if (!checkEnabled) {
			return true;
		}
		if (expectedTitle == null || expectedTitle.isBlank() || actualTitle == null) {
			return false;
		}
		return expectedTitle.trim().equals(actualTitle.trim());
	}
}
