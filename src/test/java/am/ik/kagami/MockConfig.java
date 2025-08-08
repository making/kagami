package am.ik.kagami;

import am.ik.kagami.mockserver.MockServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.util.TestSocketUtils;

@TestConfiguration(proxyBeanMethods = false)
public class MockConfig {

	@Bean
	MockServer mockServer() {
		int availableTcpPort = TestSocketUtils.findAvailableTcpPort();
		MockServer mockServer = new MockServer(availableTcpPort);
		mockServer.run();
		return mockServer;
	}

	@Bean
	DynamicPropertyRegistrar mockServerDynamicPropertyRegistrar(MockServer mockServer) {
		return registry -> {
			int port = mockServer.port();
			registry.add("kagami.repositories.mock.url", () -> "http://127.0.0.1:%d".formatted(port));
		};
	}

}
