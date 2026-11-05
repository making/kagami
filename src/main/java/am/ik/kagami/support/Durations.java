package am.ik.kagami.support;

import java.time.Duration;

/**
 * Converts user-supplied whole-number amounts into {@link Duration}s, rejecting values
 * outside an explicit range before they reach date arithmetic. Every caller states the
 * range it accepts, so an amount that would overflow the conversion or the following
 * instant arithmetic is rejected as invalid input instead of failing at the sink.
 */
public final class Durations {

	private Durations() {
	}

	/**
	 * Returns a duration of the given number of hours.
	 * @param hours the requested number of hours
	 * @param maxHours the largest accepted number of hours
	 * @param label the input name used in the rejection message
	 * @return the duration
	 * @throws IllegalArgumentException if {@code hours} is outside {@code 1..maxHours}
	 */
	public static Duration hours(long hours, long maxHours, String label) {
		requireRange(hours, maxHours, label, "hours");
		return Duration.ofHours(hours);
	}

	/**
	 * Returns a duration of the given number of days.
	 * @param days the requested number of days
	 * @param maxDays the largest accepted number of days
	 * @param label the input name used in the rejection message
	 * @return the duration
	 * @throws IllegalArgumentException if {@code days} is outside {@code 1..maxDays}
	 */
	public static Duration days(long days, long maxDays, String label) {
		requireRange(days, maxDays, label, "days");
		return Duration.ofDays(days);
	}

	/**
	 * Returns a duration of the given number of 30-day months.
	 * @param months the requested number of months
	 * @param maxMonths the largest accepted number of months
	 * @param label the input name used in the rejection message
	 * @return the duration
	 * @throws IllegalArgumentException if {@code months} is outside {@code 1..maxMonths}
	 */
	public static Duration months(long months, long maxMonths, String label) {
		requireRange(months, maxMonths, label, "months");
		try {
			return Duration.ofDays(Math.multiplyExact(months, 30));
		}
		catch (ArithmeticException e) {
			throw new IllegalArgumentException(message(label, maxMonths, "months"), e);
		}
	}

	private static void requireRange(long amount, long max, String label, String unit) {
		if (amount < 1 || amount > max) {
			throw new IllegalArgumentException(message(label, max, unit));
		}
	}

	private static String message(String label, long max, String unit) {
		return "%s must be between 1 and %d %s.".formatted(label, max, unit);
	}

}
