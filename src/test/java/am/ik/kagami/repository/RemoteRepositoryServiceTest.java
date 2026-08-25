package am.ik.kagami.repository;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.proxy.ProxySettings;
import am.ik.kagami.storage.StorageService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RemoteRepositoryService
 */
@ExtendWith(MockitoExtension.class)
class RemoteRepositoryServiceTest {

	@Mock
	private StorageService storageService;

	private static KagamiProperties properties(Map<String, KagamiProperties.Repository> repositories,
			KagamiProperties.Proxy proxy) {
		return KagamiProperties.builder()
			.storage(new KagamiProperties.Storage("/tmp"))
			.repositories(repositories)
			.proxy(proxy)
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(KagamiProperties.AuthenticationType.SIMPLE, List.of()))
			.build();
	}

	private RemoteRepositoryService service(KagamiProperties properties) {
		return new RemoteRepositoryService(properties, this.storageService, RestClient.builder(),
				ProxySettings.from(properties.proxy(), name -> null));
	}

	@Test
	void testProxyConfigurationPrecedence() {
		// Test property-based proxy configuration
		KagamiProperties properties = properties(Map.of("test",
				KagamiProperties.Repository.builder()
					.url("http://example.com")
					.username("")
					.password("")
					.isPrivate(true)
					.build()),
				KagamiProperties.Proxy.builder().url("http://config-proxy:8080").build());

		// Verify service was created successfully
		assertThat(service(properties).isRepositoryConfigured("test")).isTrue();
	}

	@Test
	void testEmptyProxyConfiguration() {
		// Test with no proxy configuration
		KagamiProperties properties = properties(Map.of("test",
				KagamiProperties.Repository.builder()
					.url("http://example.com")
					.username("")
					.password("")
					.isPrivate(true)
					.build()),
				KagamiProperties.Proxy.builder().url("").build());

		// Verify service was created successfully
		assertThat(service(properties).isRepositoryConfigured("test")).isTrue();
	}

	@Test
	void testBasicAuthConfiguration() {
		// Test Basic authentication configuration
		KagamiProperties properties = properties(Map.of("authenticated-repo",
				KagamiProperties.Repository.builder()
					.url("http://private.example.com")
					.username("user")
					.password("pass")
					.isPrivate(true)
					.build()),
				KagamiProperties.Proxy.builder().url("").build());

		// Verify service was created successfully
		assertThat(service(properties).isRepositoryConfigured("authenticated-repo")).isTrue();
	}

}
