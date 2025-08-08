package am.ik.kagami.repository;

import am.ik.kagami.KagamiProperties;
import am.ik.kagami.storage.StorageService;
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

	@Test
	void testProxyConfigurationPrecedence() {
		// Test property-based proxy configuration
		var properties = new KagamiProperties(new KagamiProperties.Storage("/tmp"),
				Map.of("test", new KagamiProperties.Repository("http://example.com", "", "", true)),
				new KagamiProperties.Proxy("http://config-proxy:8080"), new KagamiProperties.Jwt(null, null));

		var service = new RemoteRepositoryService(properties, storageService, RestClient.builder());

		// Verify service was created successfully
		assertThat(service.isRepositoryConfigured("test")).isTrue();
	}

	@Test
	void testEmptyProxyConfiguration() {
		// Test with no proxy configuration
		var properties = new KagamiProperties(new KagamiProperties.Storage("/tmp"),
				Map.of("test", new KagamiProperties.Repository("http://example.com", "", "", true)),
				new KagamiProperties.Proxy(""), new KagamiProperties.Jwt(null, null));

		var service = new RemoteRepositoryService(properties, storageService, RestClient.builder());

		// Verify service was created successfully
		assertThat(service.isRepositoryConfigured("test")).isTrue();
	}

	@Test
	void testBasicAuthConfiguration() {
		// Test Basic authentication configuration
		var properties = new KagamiProperties(new KagamiProperties.Storage("/tmp"),
				Map.of("authenticated-repo",
						new KagamiProperties.Repository("http://private.example.com", "user", "pass", true)),
				new KagamiProperties.Proxy(""), new KagamiProperties.Jwt(null, null));

		var service = new RemoteRepositoryService(properties, storageService, RestClient.builder());

		// Verify service was created successfully
		assertThat(service.isRepositoryConfigured("authenticated-repo")).isTrue();
	}

}