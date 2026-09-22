package am.ik.kagami.browser.web;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Formatting helpers shared by the server-rendered views. They mirror the client-side
 * formatting that the previous single page application applied, so the rendered values
 * stay the same.
 */
final class Formats {

	private static final String[] SIZE_UNITS = { "B", "KB", "MB", "GB", "TB" };

	private Formats() {
	}

	/**
	 * Format a size in bytes as a human readable string, e.g. {@code 17 B} or
	 * {@code 1.5 MB}.
	 */
	static String fileSize(long bytes) {
		double size = bytes;
		int unitIndex = 0;
		while (size >= 1024 && unitIndex < SIZE_UNITS.length - 1) {
			size /= 1024;
			unitIndex++;
		}
		return unitIndex == 0 ? String.format(Locale.ROOT, "%.0f %s", size, SIZE_UNITS[unitIndex])
				: String.format(Locale.ROOT, "%.1f %s", size, SIZE_UNITS[unitIndex]);
	}

	/**
	 * Format a timestamp as a localized date and time string.
	 */
	static String date(Instant instant) {
		return DateTimeFormatter.ofPattern("yyyy/M/d HH:mm:ss").withZone(ZoneId.systemDefault()).format(instant);
	}

	/**
	 * Format a timestamp relative to now: Today, Yesterday, "N days ago" or a localized
	 * date for anything older.
	 */
	static String relativeTime(Instant instant) {
		long diffDays = ChronoUnit.DAYS.between(instant, Instant.now());
		if (diffDays <= 0) {
			return "Today";
		}
		else if (diffDays == 1) {
			return "Yesterday";
		}
		else if (diffDays < 7) {
			return diffDays + " days ago";
		}
		return DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT)
			.withLocale(Locale.getDefault())
			.withZone(ZoneId.systemDefault())
			.format(instant);
	}

}
