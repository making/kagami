package am.ik.kagami.repository.web;

import am.ik.kagami.repository.RemoteRepositoryService;
import am.ik.kagami.repository.RepositoryGarbageCollector;
import am.ik.kagami.repository.RepositoryGarbageCollector.GarbageCollectionResult;
import am.ik.kagami.repository.RepositoryGarbageCollector.GarbageDirectory;
import am.ik.kagami.repository.RepositoryRetentionService;
import am.ik.kagami.repository.RepositoryRetentionService.RetentionResult;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provides the administrator API for collecting eligible leftover directories.
 */
@RestController
@RequestMapping("/artifacts")
public class RepositoryGarbageCollectionController {

	private final RepositoryGarbageCollector garbageCollector;

	private final RepositoryRetentionService retentionService;

	private final RemoteRepositoryService remoteRepositoryService;

	public RepositoryGarbageCollectionController(RepositoryGarbageCollector garbageCollector,
			RepositoryRetentionService retentionService, RemoteRepositoryService remoteRepositoryService) {
		this.garbageCollector = garbageCollector;
		this.retentionService = retentionService;
		this.remoteRepositoryService = remoteRepositoryService;
	}

	/**
	 * Preview the eligible leftover directories that would be collected.
	 */
	@GetMapping("/{repositoryId}/gc")
	public ResponseEntity<List<GarbageDirectory>> findCandidates(@PathVariable String repositoryId,
			@RequestParam(name = "olderThan", defaultValue = "PT1H") Duration olderThan) {
		if (!this.remoteRepositoryService.isRepositoryConfigured(repositoryId)) {
			return ResponseEntity.notFound().build();
		}
		if (olderThan.isNegative()) {
			return ResponseEntity.badRequest().build();
		}
		try {
			return ResponseEntity.ok(this.garbageCollector.findCandidates(repositoryId, olderThan));
		}
		catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
		catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().build();
		}
	}

	/**
	 * Apply the configured retention policy to one repository now. A repository without a
	 * policy returns an empty result and keeps every artifact.
	 */
	@PostMapping("/{repositoryId}/retention")
	public ResponseEntity<RetentionResult> applyRetention(@PathVariable String repositoryId) {
		if (!this.remoteRepositoryService.isRepositoryConfigured(repositoryId)) {
			return ResponseEntity.notFound().build();
		}
		try {
			return ResponseEntity.ok(this.retentionService.apply(repositoryId));
		}
		catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	/**
	 * Collect the eligible leftover directories that pass the age check.
	 */
	@PostMapping("/{repositoryId}/gc")
	public ResponseEntity<GarbageCollectionResult> collect(@PathVariable String repositoryId,
			@RequestParam(name = "olderThan", defaultValue = "PT1H") Duration olderThan) {
		if (!this.remoteRepositoryService.isRepositoryConfigured(repositoryId)) {
			return ResponseEntity.notFound().build();
		}
		if (olderThan.isNegative()) {
			return ResponseEntity.badRequest().build();
		}
		try {
			return ResponseEntity.ok(this.garbageCollector.collect(repositoryId, olderThan));
		}
		catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
		catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().build();
		}
	}

}
