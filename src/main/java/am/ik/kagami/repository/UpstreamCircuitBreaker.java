package am.ik.kagami.repository;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * A per-repository circuit breaker for outbound artifact fetches.
 * <p>
 * Once a repository has failed {@code failureThreshold} times in a row, further fetches
 * are short-circuited for {@code openDuration}. A short-circuited fetch returns
 * immediately instead of paying the upstream timeout again, which is what bounds the work
 * an unauthenticated cache miss can trigger while an upstream is down. The breaker does
 * not cache artifacts, so a successful fetch always goes upstream.
 */
class UpstreamCircuitBreaker {

	private final int failureThreshold;

	private final Duration openDuration;

	private final InstantSource clock;

	private final ConcurrentMap<String, State> states = new ConcurrentHashMap<>();

	UpstreamCircuitBreaker(int failureThreshold, Duration openDuration, InstantSource clock) {
		if (failureThreshold < 1) {
			throw new IllegalArgumentException("failureThreshold must be at least 1");
		}
		this.failureThreshold = failureThreshold;
		this.openDuration = openDuration;
		this.clock = clock;
	}

	/**
	 * Whether a fetch for the repository may run now.
	 * @param repositoryId the repository about to be contacted
	 * @return {@code true} unless the breaker is open
	 */
	boolean allowsRequest(String repositoryId) {
		State state = this.states.get(repositoryId);
		if (state == null) {
			return true;
		}
		synchronized (state) {
			if (state.openUntil == null) {
				return true;
			}
			if (!this.clock.instant().isBefore(state.openUntil)) {
				// Half open: let one request through and start a fresh streak
				state.openUntil = null;
				state.consecutiveFailures = 0;
				return true;
			}
			return false;
		}
	}

	void recordSuccess(String repositoryId) {
		State state = this.states.get(repositoryId);
		if (state != null) {
			synchronized (state) {
				state.consecutiveFailures = 0;
				state.openUntil = null;
			}
		}
	}

	void recordFailure(String repositoryId) {
		State state = this.states.computeIfAbsent(repositoryId, key -> new State());
		synchronized (state) {
			state.consecutiveFailures++;
			if (state.consecutiveFailures >= this.failureThreshold) {
				state.openUntil = this.clock.instant().plus(this.openDuration);
			}
		}
	}

	private static final class State {

		private int consecutiveFailures;

		private @Nullable Instant openUntil;

	}

}
