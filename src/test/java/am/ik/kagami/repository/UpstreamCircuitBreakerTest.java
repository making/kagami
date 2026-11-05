package am.ik.kagami.repository;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that a failing upstream is short-circuited instead of being retried by every
 * request.
 */
class UpstreamCircuitBreakerTest {

	@Test
	void circuitOpensAfterTheConfiguredNumberOfFailuresAndClosesAfterTheCooldown() {
		MutableInstantSource clock = new MutableInstantSource(Instant.parse("2026-01-01T00:00:00Z"));
		UpstreamCircuitBreaker breaker = new UpstreamCircuitBreaker(2, Duration.ofSeconds(30), clock);

		assertThat(breaker.allowsRequest("repo")).isTrue();

		breaker.recordFailure("repo");
		assertThat(breaker.allowsRequest("repo")).as("one failure does not open the circuit").isTrue();

		breaker.recordFailure("repo");
		assertThat(breaker.allowsRequest("repo")).as("threshold reached").isFalse();
		assertThat(breaker.allowsRequest("other")).as("the breaker is per repository").isTrue();

		clock.advance(Duration.ofSeconds(31));
		assertThat(breaker.allowsRequest("repo")).as("cooldown elapsed").isTrue();
	}

	@Test
	void successResetsTheFailureStreak() {
		MutableInstantSource clock = new MutableInstantSource(Instant.parse("2026-01-01T00:00:00Z"));
		UpstreamCircuitBreaker breaker = new UpstreamCircuitBreaker(2, Duration.ofSeconds(30), clock);

		breaker.recordFailure("repo");
		breaker.recordSuccess("repo");
		breaker.recordFailure("repo");

		assertThat(breaker.allowsRequest("repo")).isTrue();
	}

	private static final class MutableInstantSource implements InstantSource {

		private Instant instant;

		private MutableInstantSource(Instant instant) {
			this.instant = instant;
		}

		private void advance(Duration duration) {
			this.instant = this.instant.plus(duration);
		}

		@Override
		public Instant instant() {
			return this.instant;
		}

	}

}
