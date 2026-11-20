package am.ik.kagami.repository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the configured artifact retention policy on a schedule.
 * <p>
 * The sweep interval is configurable with {@code kagami.retention.sweep-interval}
 * (default one hour). The first sweep is delayed by
 * {@code kagami.retention.initial-delay} (default one minute) so that application startup
 * is not spent walking storage. Repositories without a retention policy are skipped
 * without touching storage.
 */
@Component
class RepositoryRetentionScheduler {

	private final RepositoryRetentionService retentionService;

	RepositoryRetentionScheduler(RepositoryRetentionService retentionService) {
		this.retentionService = retentionService;
	}

	@Scheduled(fixedDelayString = "${kagami.retention.sweep-interval:PT1H}",
			initialDelayString = "${kagami.retention.initial-delay:PT1M}")
	void sweep() {
		this.retentionService.applyAll();
	}

}
