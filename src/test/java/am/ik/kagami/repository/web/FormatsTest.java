package am.ik.kagami.repository.web;

import java.time.Instant;
import java.time.InstantSource;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Formats#relativeTime(Instant, InstantSource)}. The zone used to build
 * the instants is the system default because the formatting resolves calendar days in the
 * system time zone.
 */
class FormatsTest {

	private static final java.time.ZoneId ZONE = java.time.ZoneId.systemDefault();

	private static InstantSource nowAt(ZonedDateTime dateTime) {
		return InstantSource.fixed(dateTime.toInstant());
	}

	@Test
	void todayWhenModifiedOnSameCalendarDay() {
		// modified at 00:05 today, less than 10 hours before "now"
		Instant instant = ZonedDateTime.of(2026, 9, 24, 0, 5, 0, 0, ZONE).toInstant();
		assertThat(Formats.relativeTime(instant, nowAt(ZonedDateTime.of(2026, 9, 24, 10, 0, 0, 0, ZONE))))
			.isEqualTo("Today");
	}

	@Test
	void yesterdayWhenModifiedOnPreviousCalendarDayEvenWithin24Hours() {
		// modified yesterday 23:00, only 11 hours ago, but a different calendar day
		Instant instant = ZonedDateTime.of(2026, 9, 23, 23, 0, 0, 0, ZONE).toInstant();
		assertThat(Formats.relativeTime(instant, nowAt(ZonedDateTime.of(2026, 9, 24, 10, 0, 0, 0, ZONE))))
			.isEqualTo("Yesterday");
	}

	@Test
	void daysAgoWithinAWeek() {
		Instant instant = ZonedDateTime.of(2026, 9, 21, 12, 0, 0, 0, ZONE).toInstant();
		assertThat(Formats.relativeTime(instant, nowAt(ZonedDateTime.of(2026, 9, 24, 10, 0, 0, 0, ZONE))))
			.isEqualTo("3 days ago");
	}

}
