package am.ik.kagami.proxy;

import am.ik.kagami.KagamiProperties;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ProxySettings
 */
class ProxySettingsTest {

	static Function<String, @Nullable String> environment(Map<String, String> variables) {
		return variables::get;
	}

	static Function<String, @Nullable String> noEnvironment() {
		return environment(Map.of());
	}

	static KagamiProperties.Proxy proxy(@Nullable String url) {
		return KagamiProperties.Proxy.builder().url(url).build();
	}

	@Test
	void noProxyConfigured() {
		ProxySettings settings = ProxySettings.from(null, noEnvironment());

		assertThat(settings.hasProxy()).isFalse();
		assertThat(settings.proxyFor("https://repo.maven.apache.org/maven2")).isEmpty();
		assertThat(settings.toAuthenticator()).isEmpty();
	}

	@Test
	void blankProxyUrlIsIgnored() {
		ProxySettings settings = ProxySettings.from(proxy(""), noEnvironment());

		assertThat(settings.hasProxy()).isFalse();
	}

	@Test
	void proxyUrlPropertyTakesPrecedenceOverEnvironmentVariables() {
		ProxySettings settings = ProxySettings.from(proxy("http://config-proxy:8080"),
				environment(Map.of("http_proxy", "http://env-proxy:3128")));

		assertThat(settings.proxyFor("http://example.com/maven2"))
			.hasValueSatisfying(httpProxy -> assertThat(httpProxy.host()).isEqualTo("config-proxy"));
	}

	@Test
	void lowerCaseEnvironmentVariableTakesPrecedenceOverUpperCase() {
		ProxySettings settings = ProxySettings.from(null,
				environment(Map.of("http_proxy", "http://lower-proxy:3128", "HTTP_PROXY", "http://upper-proxy:3128")));

		assertThat(settings.proxyFor("http://example.com/maven2"))
			.hasValueSatisfying(httpProxy -> assertThat(httpProxy.host()).isEqualTo("lower-proxy"));
	}

	@Test
	void upperCaseEnvironmentVariableIsUsedAsFallback() {
		ProxySettings settings = ProxySettings.from(null, environment(Map.of("HTTP_PROXY", "http://upper-proxy:3128",
				"HTTPS_PROXY", "http://upper-secure-proxy:3128", "NO_PROXY", "internal.example.com")));

		assertThat(settings.proxyFor("http://example.com/maven2"))
			.hasValueSatisfying(httpProxy -> assertThat(httpProxy.host()).isEqualTo("upper-proxy"));
		assertThat(settings.proxyFor("https://example.com/maven2"))
			.hasValueSatisfying(httpProxy -> assertThat(httpProxy.host()).isEqualTo("upper-secure-proxy"));
		assertThat(settings.proxyFor("http://internal.example.com/maven2")).isEmpty();
	}

	@Test
	void httpsProxyIsUsedForSecureRepositories() {
		ProxySettings settings = ProxySettings.from(
				KagamiProperties.Proxy.builder().url("http://proxy:8080").httpsUrl("http://secure-proxy:8443").build(),
				noEnvironment());

		assertThat(settings.proxyFor("http://example.com/maven2"))
			.hasValueSatisfying(httpProxy -> assertThat(httpProxy.port()).isEqualTo(8080));
		assertThat(settings.proxyFor("https://example.com/maven2"))
			.hasValueSatisfying(httpProxy -> assertThat(httpProxy.port()).isEqualTo(8443));
	}

	@Test
	void httpProxyIsUsedForSecureRepositoriesWhenNoHttpsProxyIsConfigured() {
		ProxySettings settings = ProxySettings.from(proxy("http://proxy:8080"), noEnvironment());

		assertThat(settings.proxyFor("https://repo.maven.apache.org/maven2"))
			.hasValueSatisfying(httpProxy -> assertThat(httpProxy.host()).isEqualTo("proxy"));
	}

	@Test
	void proxyUrlWithoutSchemeIsAcceptedForLegacyEnvironments() {
		ProxySettings settings = ProxySettings.from(proxy("proxy.company.com:8080"), noEnvironment());

		assertThat(settings.proxyFor("http://example.com/maven2"))
			.hasValue(HttpProxy.builder().scheme("http").host("proxy.company.com").port(8080).build());
	}

	@Test
	void proxyUrlWithoutPortFallsBackToTheDefaultPort() {
		ProxySettings settings = ProxySettings.from(proxy("http://proxy.company.com"), noEnvironment());

		assertThat(settings.proxyFor("http://example.com/maven2"))
			.hasValueSatisfying(httpProxy -> assertThat(httpProxy.port()).isEqualTo(80));
	}

	@Test
	void credentialsAreReadFromTheProxyUrl() {
		ProxySettings settings = ProxySettings.from(proxy("http://user%40corp:p%40ss@proxy.company.com:8080"),
				noEnvironment());

		assertThat(settings.proxyFor("http://example.com/maven2")).hasValueSatisfying(httpProxy -> {
			assertThat(httpProxy.hasCredentials()).isTrue();
			assertThat(httpProxy.username()).isEqualTo("user@corp");
			assertThat(httpProxy.password()).isEqualTo("p@ss");
		});
		assertThat(settings.toAuthenticator()).isPresent();
	}

	@Test
	void credentialsAreReadFromTheProperties() {
		ProxySettings settings = ProxySettings.from(KagamiProperties.Proxy.builder()
			.url("http://proxy.company.com:8080")
			.username("user")
			.password("secret")
			.build(), noEnvironment());

		assertThat(settings.proxyFor("http://example.com/maven2")).hasValueSatisfying(httpProxy -> {
			assertThat(httpProxy.username()).isEqualTo("user");
			assertThat(httpProxy.password()).isEqualTo("secret");
		});
	}

	@Test
	void credentialsAreOnlyProvidedForTheConfiguredProxy() {
		ProxySettings settings = ProxySettings.from(proxy("http://user:secret@proxy.company.com:8080"),
				noEnvironment());

		assertThat(settings.credentialsFor("proxy.company.com", 8080))
			.hasValueSatisfying(credentials -> assertThat(credentials.getUserName()).isEqualTo("user"));
		assertThat(settings.credentialsFor("proxy.company.com", 3128)).isEmpty();
		assertThat(settings.credentialsFor("evil.example.com", 8080)).isEmpty();
	}

	@Test
	void nonProxyHostsMatchTheHostAndItsSubDomains() {
		ProxySettings settings = ProxySettings.from(KagamiProperties.Proxy.builder()
			.url("http://proxy:8080")
			.nonProxyHosts(List.of("localhost", "example.com"))
			.build(), noEnvironment());

		assertThat(settings.proxyFor("http://localhost:8080/maven2")).isEmpty();
		assertThat(settings.proxyFor("http://example.com/maven2")).isEmpty();
		assertThat(settings.proxyFor("http://repo.example.com/maven2")).isEmpty();
		assertThat(settings.proxyFor("http://counterexample.com/maven2")).isPresent();
	}

	@Test
	void nonProxyHostsAcceptWildcardAndLeadingDotNotations() {
		ProxySettings settings = ProxySettings.from(KagamiProperties.Proxy.builder()
			.url("http://proxy:8080")
			.nonProxyHosts(List.of("*.example.com", ".example.org"))
			.build(), noEnvironment());

		assertThat(settings.proxyFor("http://repo.example.com/maven2")).isEmpty();
		assertThat(settings.proxyFor("http://repo.example.org/maven2")).isEmpty();
		assertThat(settings.proxyFor("http://example.net/maven2")).isPresent();
	}

	@Test
	void nonProxyHostsCanBeQualifiedWithAPort() {
		ProxySettings settings = ProxySettings.from(KagamiProperties.Proxy.builder()
			.url("http://proxy:8080")
			.nonProxyHosts(List.of("example.com:8081"))
			.build(), noEnvironment());

		assertThat(settings.proxyFor("http://example.com:8081/maven2")).isEmpty();
		assertThat(settings.proxyFor("http://example.com/maven2")).isPresent();
	}

	@Test
	void nonProxyHostsAreReadFromTheEnvironment() {
		ProxySettings settings = ProxySettings.from(null,
				environment(Map.of("http_proxy", "http://proxy:8080", "no_proxy", "localhost, .example.com")));

		assertThat(settings.proxyFor("http://localhost/maven2")).isEmpty();
		assertThat(settings.proxyFor("http://repo.example.com/maven2")).isEmpty();
		assertThat(settings.proxyFor("http://example.net/maven2")).isPresent();
	}

	@Test
	void nonProxyHostsPropertyTakesPrecedenceOverTheEnvironment() {
		ProxySettings settings = ProxySettings.from(
				KagamiProperties.Proxy.builder().url("http://proxy:8080").nonProxyHosts(List.of("example.net")).build(),
				environment(Map.of("no_proxy", "example.com")));

		assertThat(settings.proxyFor("http://example.net/maven2")).isEmpty();
		assertThat(settings.proxyFor("http://example.com/maven2")).isPresent();
	}

	@Test
	void everyHostCanBeExcludedWithAnAsterisk() {
		ProxySettings settings = ProxySettings.from(null,
				environment(Map.of("http_proxy", "http://proxy:8080", "no_proxy", "*")));

		assertThat(settings.hasProxy()).isTrue();
		assertThat(settings.proxyFor("http://example.com/maven2")).isEmpty();
	}

	@Test
	void proxySelectorReflectsTheSettings() {
		ProxySettings settings = ProxySettings.from(
				KagamiProperties.Proxy.builder().url("http://proxy:8080").nonProxyHosts(List.of("localhost")).build(),
				noEnvironment());

		assertThat(settings.toProxySelector().select(URI.create("http://example.com/maven2"))).singleElement()
			.satisfies(selected -> {
				assertThat(selected.type()).isEqualTo(java.net.Proxy.Type.HTTP);
				InetSocketAddress address = (InetSocketAddress) selected.address();
				assertThat(address.getHostString()).isEqualTo("proxy");
				assertThat(address.getPort()).isEqualTo(8080);
			});
		assertThat(settings.toProxySelector().select(URI.create("http://localhost/maven2")))
			.containsExactly(java.net.Proxy.NO_PROXY);
	}

	@Test
	void invalidProxyUrlIsIgnored() {
		ProxySettings settings = ProxySettings.from(proxy("http://"), noEnvironment());

		assertThat(settings.hasProxy()).isFalse();
	}

}
