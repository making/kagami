package am.ik.kagami.repository.web;

import am.ik.kagami.repository.RemoteRepositoryService;
import am.ik.kagami.repository.RepositoryGarbageCollector;
import am.ik.kagami.repository.RepositoryGarbageCollector.GarbageCollectionResult;
import am.ik.kagami.repository.RepositoryGarbageCollector.GarbageDirectory;
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
 * Provides the administrator API for collecting eligible cache-bookkeeping directories.
 */
@RestController
@RequestMapping("/artifacts")
public class RepositoryGarbageCollectionController {

	private final RepositoryGarbageCollector garbageCollector;

	private final RemoteRepositoryService remoteRepositoryService;

	public RepositoryGarbageCollectionController(RepositoryGarbageCollector garbageCollector,
			RemoteRepositoryService remoteRepositoryService) {
		this.garbageCollector = garbageCollector;
		this.remoteRepositoryService = remoteRepositoryService;
	}

	/**
	 * Preview the eligible cache-bookkeeping directories that would be collected.
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
	 * Collect the eligible cache-bookkeeping directories that pass the age check.
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
