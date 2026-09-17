package ovh.unlimitedbytes.autosell.util;

import java.util.Locale;

/**
 * Tiny fuzzy matcher for the allowlist search: every whitespace-separated query
 * token must appear in the target as a case-insensitive in-order subsequence
 * ("drk" matches "dark_dye", "melon sed" matches "melon_seeds"). The score
 * rewards word-start and contiguous hits and prefers shorter targets, so the
 * natural candidates rank first. Any input is safe: null/blank queries match
 * everything with score 0, unmatched tokens simply return -1.
 */
public final class FuzzyMatcher {
	private FuzzyMatcher() {
	}

	/** True when every query token fuzzy-matches the target. */
	public static boolean isMatch(String query, String target) {
		return scoreTokens(query, target) >= 0;
	}

	/**
	 * Summed score of all query tokens against the target; -1 when any token does
	 * not match. Higher is better; a blank query matches with score 0.
	 */
	public static int scoreTokens(String query, String target) {
		if (query == null || target == null) {
			return -1;
		}
		String normalized = query.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) {
			return 0;
		}
		int total = 0;
		for (String token : normalized.split("\\s+")) {
			int score = score(token, target);
			if (score < 0) {
				return -1;
			}
			total += score;
		}
		return total;
	}

	private static int score(String token, String targetLower) {
		if (token.length() > targetLower.length()) {
			return -1;
		}
		int score = 0;
		int from = 0;
		int previous = -1;
		for (int i = 0; i < token.length(); i++) {
			int index = targetLower.indexOf(token.charAt(i), from);
			if (index < 0) {
				return -1;
			}
			score += 1;
			if (index == previous + 1) {
				score += 3; // contiguous run reads as one word fragment
			}
			if (index == 0 || isWordBoundary(targetLower.charAt(index - 1))) {
				score += 5; // start of the id, a word or a namespace
			}
			previous = index;
			from = index + 1;
		}
		return score - targetLower.length() / 8; // slight preference for shorter targets
	}

	private static boolean isWordBoundary(char c) {
		return c == '_' || c == ' ' || c == ':' || c == '-' || c == '.';
	}
}
