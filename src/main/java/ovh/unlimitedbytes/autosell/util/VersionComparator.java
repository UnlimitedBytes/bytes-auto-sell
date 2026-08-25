package ovh.unlimitedbytes.autosell.util;

/**
 * Compares GitHub release tags against the local mod version. Accepts the tag form
 * ({@code v2.1.0}) and the loader's friendly version form ({@code 2.1.0+mc26.2});
 * build metadata after {@code +} and pre-release suffixes after {@code -} are
 * ignored. Comparison is numeric per component, up to three components (missing
 * components count as 0). Anything unparseable fails closed: never reported as
 * newer, so a malformed tag can never nag the user.
 */
public final class VersionComparator {
	private VersionComparator() {
	}

	/** True when {@code remote} describes a strictly newer release than {@code local}. */
	public static boolean isNewer(String remote, String local) {
		int[] r = parse(remote);
		int[] l = parse(local);
		if (r == null || l == null) {
			return false;
		}
		for (int i = 0; i < 3; i++) {
			if (r[i] != l[i]) {
				return r[i] > l[i];
			}
		}
		return false;
	}

	/**
	 * Normalizes a tag or version string to {@code MAJOR.MINOR.PATCH}; null when no
	 * leading version number can be parsed.
	 */
	public static String normalize(String version) {
		int[] parsed = parse(version);
		if (parsed == null) {
			return null;
		}
		return parsed[0] + "." + parsed[1] + "." + parsed[2];
	}

	private static int[] parse(String version) {
		if (version == null) {
			return null;
		}
		String v = version.trim();
		if (v.startsWith("v") || v.startsWith("V")) {
			v = v.substring(1);
		}
		int cut = v.length();
		int plus = v.indexOf('+');
		if (plus >= 0) {
			cut = Math.min(cut, plus);
		}
		int dash = v.indexOf('-');
		if (dash >= 0) {
			cut = Math.min(cut, dash);
		}
		v = v.substring(0, cut);
		if (v.isEmpty()) {
			return null;
		}
		String[] parts = v.split("\\.");
		if (parts.length > 3) {
			return null;
		}
		int[] numbers = new int[3];
		for (int i = 0; i < parts.length; i++) {
			try {
				numbers[i] = Integer.parseInt(parts[i]);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return numbers;
	}
}
