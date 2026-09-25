package am.ik.kagami.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for RepositoryService
 */
class RepositoryServiceTest {

	private RepositoryService service(Map<String, KagamiProperties.Repository> repositories) {
		KagamiProperties properties = KagamiProperties.builder()
			.storage(KagamiProperties.Storage.builder().path("/tmp").build())
			.repositories(repositories)
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(KagamiProperties.AuthenticationType.SIMPLE, List.of()))
			.build();
		return new RepositoryService(mock(StorageService.class), properties, mock(AsyncTaskExecutor.class));
	}

	@Test
	void repositoriesAreSortedByPriorityDescending() {
		Map<String, KagamiProperties.Repository> repositories = new LinkedHashMap<>();
		repositories.put("low",
				KagamiProperties.Repository.builder().url("http://example.com/low").priority(-1).build());
		repositories.put("high",
				KagamiProperties.Repository.builder().url("http://example.com/high").priority(10).build());
		repositories.put("default", KagamiProperties.Repository.builder().url("http://example.com/default").build());
		RepositoryService service = service(repositories);
		assertThat(service.getRepositories()).extracting(RepositoryService.RepositorySummary::id)
			.containsExactly("high", "default", "low");
	}

	@Test
	void equalPrioritiesKeepConfigurationOrder() {
		Map<String, KagamiProperties.Repository> repositories = new LinkedHashMap<>();
		repositories.put("zeta", KagamiProperties.Repository.builder().url("http://example.com/zeta").build());
		repositories.put("alpha", KagamiProperties.Repository.builder().url("http://example.com/alpha").build());
		RepositoryService service = service(repositories);
		assertThat(service.getRepositories()).extracting(RepositoryService.RepositorySummary::id)
			.containsExactly("zeta", "alpha");
	}

	@Test
	void defaultPriorityIsZero() {
		RepositoryService service = service(
				Map.of("repo", KagamiProperties.Repository.builder().url("http://example.com").build()));
		assertThat(service.getRepositories()).singleElement().satisfies(repo -> {
			assertThat(repo.priority()).isZero();
		});
	}

}
