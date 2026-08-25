package am.ik.kagami.proxy;

import am.ik.kagami.KagamiProperties;
import java.io.IOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Resolved HTTP proxy settings for outgoing connections.
 * <p>
 * Values are taken from the {@code kagami.proxy.*} properties first and fall back to the
 * environment variables that are the de facto standard in legacy environments:
 * {@code http_proxy}, {@code https_proxy} and {@code no_proxy}, each of which is also
 * looked up in upper case.
 * <p>
 * When only a proxy for {@code http} is configured it is used for {@code https} as well,
 * because legacy environments usually route every protocol through a single proxy.
 */
public final class ProxySettings {

	private static final Logger logger = LoggerFactory.getLogger(ProxySettings.class);

	private static final ProxySettings NONE = new ProxySettings(null, null, List.of());

	private final HttpProxy httpProxy;

	private final HttpProxy httpsProxy;

	private final List<String> nonProxyHosts;

	private ProxySettings(HttpProxy httpProxy, HttpProxy httpsProxy, List<String> nonProxyHosts) {
		this.httpProxy = httpProxy;
		this.httpsProxy = httpsProxy;
		this.nonProxyHosts = nonProxyHosts;
	}

	/**
	 * Resolve the proxy settings from the given properties and the environment variables
	 * of the running process.
	 * @param proxy proxy properties, may be {@code null}
	 * @return resolved proxy settings
	 */
	public static ProxySettings from(KagamiProperties.Proxy proxy) {
		return from(proxy, System::getenv);
	}

	/**
	 * Resolve the proxy settings from the given properties and environment.
	 * @param proxy proxy properties, may be {@code null}
	 * @param environment lookup function for environment variables
	 * @return resolved proxy settings
	 */
	public static ProxySettings from(KagamiProperties.Proxy proxy, UnaryOperator<String> environment) {
		String username = proxy != null ? proxy.username() : null;
		String password = proxy != null ? proxy.password() : null;
		HttpProxy httpProxy = parse(firstWithText(proxy != null ? proxy.url() : null, environment.apply("http_proxy"),
				environment.apply("HTTP_PROXY")), username, password);
		HttpProxy httpsProxy = parse(firstWithText(proxy != null ? proxy.httpsUrl() : null,
				environment.apply("https_proxy"), environment.apply("HTTPS_PROXY")), username, password);
		if (httpsProxy == null) {
			httpsProxy = httpProxy;
		}
		List<String> nonProxyHosts = proxy != null && !proxy.nonProxyHosts().isEmpty() ? proxy.nonProxyHosts()
				: splitNonProxyHosts(firstWithText(environment.apply("no_proxy"), environment.apply("NO_PROXY")));
		if (httpProxy == null && httpsProxy == null) {
			return NONE;
		}
		logger.info("Using HTTP proxy http={} https={} nonProxyHosts={}", httpProxy, httpsProxy, nonProxyHosts);
		return new ProxySettings(httpProxy, httpsProxy, nonProxyHosts);
	}

	/**
	 * Whether any proxy is configured.
	 * @return true if at least one proxy is configured
	 */
	public boolean hasProxy() {
		return this.httpProxy != null || this.httpsProxy != null;
	}

	/**
	 * Return the proxy to use for the given target URL.
	 * @param url target URL
	 * @return the proxy to use, or {@link Optional#empty()} if the target must be reached
	 * directly
	 */
	public Optional<HttpProxy> proxyFor(String url) {
		try {
			return proxyFor(new URI(url));
		}
		catch (URISyntaxException e) {
			logger.warn("Invalid URL: {}", url, e);
			return Optional.empty();
		}
	}

	/**
	 * Return the proxy to use for the given target URI.
	 * @param uri target URI
	 * @return the proxy to use, or {@link Optional#empty()} if the target must be reached
	 * directly
	 */
	public Optional<HttpProxy> proxyFor(URI uri) {
		String host = uri.getHost();
		if (host == null) {
			return Optional.empty();
		}
		boolean secure = "https".equalsIgnoreCase(uri.getScheme());
		HttpProxy proxy = secure ? this.httpsProxy : this.httpProxy;
		if (proxy == null || isNonProxyHost(host, uri.getPort() != -1 ? uri.getPort() : (secure ? 443 : 80))) {
			return Optional.empty();
		}
		return Optional.of(proxy);
	}

	/**
	 * Return a {@link ProxySelector} that applies these settings, to be used by clients
	 * built on top of {@link java.net.http.HttpClient}.
	 * @return a proxy selector backed by these settings
	 */
	public ProxySelector toProxySelector() {
		return new ProxySettingsProxySelector(this);
	}

	/**
	 * Return an {@link Authenticator} answering the proxy authentication challenges of
	 * the configured proxies, to be used by clients built on top of
	 * {@link java.net.http.HttpClient}.
	 * @return an authenticator, or {@link Optional#empty()} if no proxy requires
	 * credentials
	 */
	public Optional<Authenticator> toAuthenticator() {
		boolean hasCredentials = (this.httpProxy != null && this.httpProxy.hasCredentials())
				|| (this.httpsProxy != null && this.httpsProxy.hasCredentials());
		return hasCredentials ? Optional.of(new ProxySettingsAuthenticator(this)) : Optional.empty();
	}

	Optional<PasswordAuthentication> credentialsFor(String host, int port) {
		if (host == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(this.httpProxy)
			.filter(proxy -> proxy.matches(host, port) && proxy.hasCredentials())
			.or(() -> Optional.ofNullable(this.httpsProxy)
				.filter(proxy -> proxy.matches(host, port) && proxy.hasCredentials()))
			.map(proxy -> new PasswordAuthentication(proxy.username(), proxy.password().toCharArray()));
	}

	/**
	 * Whether the given host is excluded from proxying. Each entry of the non proxy hosts
	 * matches the host itself and, unless it is qualified with a port, its sub domains.
	 * {@code *} excludes every host.
	 */
	private boolean isNonProxyHost(String host, int port) {
		String target = host.toLowerCase(Locale.ROOT);
		for (String nonProxyHost : this.nonProxyHosts) {
			String pattern = nonProxyHost.trim().toLowerCase(Locale.ROOT);
			if (pattern.isEmpty()) {
				continue;
			}
			if (pattern.equals("*")) {
				return true;
			}
			// Both the "*.example.com" and the ".example.com" notations are accepted
			pattern = StringUtils.trimLeadingCharacter(pattern, '*');
			pattern = StringUtils.trimLeadingCharacter(pattern, '.');
			int colonIndex = pattern.lastIndexOf(':');
			if (colonIndex > 0) {
				String patternPort = pattern.substring(colonIndex + 1);
				if (!patternPort.equals(String.valueOf(port))) {
					continue;
				}
				pattern = pattern.substring(0, colonIndex);
			}
			if (target.equals(pattern) || target.endsWith("." + pattern)) {
				return true;
			}
		}
		return false;
	}

	private static HttpProxy parse(String url, String username, String password) {
		if (!StringUtils.hasText(url)) {
			return null;
		}
		// Legacy environments often set the proxy without a scheme, e.g. "proxy:8080"
		String normalized = url.contains("://") ? url.trim() : "http://" + url.trim();
		URI uri;
		try {
			uri = new URI(normalized);
		}
		catch (URISyntaxException e) {
			logger.warn("Invalid proxy URL: {}", url, e);
			return null;
		}
		String host = uri.getHost();
		if (host == null) {
			logger.warn("Invalid proxy URL: {}", url);
			return null;
		}
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		if (!"http".equals(scheme)) {
			logger.warn("Proxy scheme '{}' is not supported, connecting to the proxy {} over http instead", scheme,
					host);
		}
		int port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
		String userInfo = uri.getUserInfo();
		if (userInfo != null) {
			int colonIndex = userInfo.indexOf(':');
			username = decode(colonIndex < 0 ? userInfo : userInfo.substring(0, colonIndex));
			password = colonIndex < 0 ? null : decode(userInfo.substring(colonIndex + 1));
		}
		return new HttpProxy(scheme, host, port, username, password);
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private static List<String> splitNonProxyHosts(String nonProxyHosts) {
		if (!StringUtils.hasText(nonProxyHosts)) {
			return List.of();
		}
		// "no_proxy" is comma separated while "http.nonProxyHosts" is pipe separated
		return Arrays.stream(nonProxyHosts.split("[,|]")).map(String::trim).filter(StringUtils::hasText).toList();
	}

	private static String firstWithText(String... values) {
		return Arrays.stream(values).filter(StringUtils::hasText).findFirst().orElse(null);
	}

	private static final class ProxySettingsProxySelector extends ProxySelector {

		private final ProxySettings proxySettings;

		private ProxySettingsProxySelector(ProxySettings proxySettings) {
			this.proxySettings = proxySettings;
		}

		@Override
		public List<Proxy> select(URI uri) {
			return this.proxySettings.proxyFor(uri)
				.map(proxy -> List.of(proxy.toJdkProxy()))
				.orElseGet(() -> List.of(Proxy.NO_PROXY));
		}

		@Override
		public void connectFailed(URI uri, SocketAddress socketAddress, IOException e) {
			logger.warn("Failed to connect to the proxy {} for {}", socketAddress, uri, e);
		}

	}

	private static final class ProxySettingsAuthenticator extends Authenticator {

		private final ProxySettings proxySettings;

		private ProxySettingsAuthenticator(ProxySettings proxySettings) {
			this.proxySettings = proxySettings;
		}

		@Override
		protected PasswordAuthentication getPasswordAuthentication() {
			if (getRequestorType() != RequestorType.PROXY) {
				return null;
			}
			return this.proxySettings.credentialsFor(getRequestingHost(), getRequestingPort()).orElse(null);
		}

	}

}
