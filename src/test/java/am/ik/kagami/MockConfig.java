package am.ik.kagami;

import am.ik.kagami.mockserver.MockServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

@TestConfiguration(proxyBeanMethods = false)
public class MockConfig {

	@Bean
	MockServer mockServer() {
		MockServer mockServer = new MockServer(0);
		mockServer.run();
		return mockServer;
	}

	@Bean
	DynamicPropertyRegistrar mockServerDynamicPropertyRegistrar(MockServer mockServer) {
		return registry -> {
			int port = mockServer.port();
			registry.add("kagami.repositories.mock.url", () -> "http://127.0.0.1:%d".formatted(port));
			// A private repository pointing at the same upstream, so that the private
			// repository UI is exercised in the browser E2E scenario
			registry.add("kagami.repositories.secret.url", () -> "http://127.0.0.1:%d".formatted(port));
			registry.add("kagami.repositories.secret.is-private", () -> "true");
		};
	}

}
