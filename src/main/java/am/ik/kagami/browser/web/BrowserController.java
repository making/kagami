package am.ik.kagami.browser.web;

import am.ik.kagami.browser.BrowserService;
import am.ik.kagami.browser.BrowserService.BrowseResult;
import am.ik.kagami.browser.BrowserService.FileInfo;
import am.ik.kagami.browser.BrowserService.RepositoryInfo;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for browsing repository contents
 */
@RestController
public class BrowserController {

	private final BrowserService browserService;

	public BrowserController(BrowserService browserService) {
		this.browserService = browserService;
	}

	/**
	 * Get all configured repositories with statistics
	 * @return list of repositories
	 */
	@GetMapping("/repositories")
	public ResponseEntity<RepositoryListResponse> getRepositories() {
		List<RepositoryInfo> repositories = this.browserService.getRepositories();
		return ResponseEntity.ok(new RepositoryListResponse(repositories));
	}

	/**
	 * Browse repository contents at specified path
	 * @param repositoryId the repository identifier
	 * @param path the path to browse (optional, defaults to root)
	 * @return browse result with entries
	 */
	@GetMapping("/repositories/{repositoryId}/browse")
	public ResponseEntity<BrowseResult> browseRepository(@PathVariable String repositoryId,
			@RequestParam(required = false) String path) {
		try {
			BrowseResult result = this.browserService.browseRepository(repositoryId, path);
			return ResponseEntity.ok(result);
		}
		catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().build();
		}
		catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	/**
	 * Get detailed information about a file
	 * @param repositoryId the repository identifier
	 * @param path the file path
	 * @return file information
	 */
	@GetMapping("/repositories/{repositoryId}/info")
	public ResponseEntity<FileInfo> getFileInfo(@PathVariable String repositoryId,
			@RequestParam(required = true) String path) {
		try {
			FileInfo info = this.browserService.getFileInfo(repositoryId, path);
			return ResponseEntity.ok(info);
		}
		catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().build();
		}
		catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	// Response wrapper for repository list
	public record RepositoryListResponse(List<RepositoryInfo> repositories) {
	}

}