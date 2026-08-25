package am.ik.kagami.proxy;

import java.net.InetSocketAddress;
import java.net.Proxy;
import org.springframework.util.StringUtils;

/**
 * A single HTTP proxy server to route outgoing requests through.
 *
 * @param scheme scheme of the proxy server itself, typically {@code http}
 * @param host host name of the proxy server
 * @param port port of the proxy server
 * @param username user name for proxy authentication, may be {@code null}
 * @param password password for proxy authentication, may be {@code null}
 */
public record HttpProxy(String scheme, String host, int port, String username, String password) {

	public boolean hasCredentials() {
		return StringUtils.hasText(this.username);
	}

	/**
	 * Whether this proxy is the one listening on the given address.
	 * @param host host name or address to compare
	 * @param port port to compare
	 * @return true if the given address points to this proxy
	 */
	public boolean matches(String host, int port) {
		return this.port == port && this.host.equalsIgnoreCase(host);
	}

	public Proxy toJdkProxy() {
		// The address is left unresolved so that the proxy host is resolved lazily, when
		// a connection is actually established.
		return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(this.host, this.port));
	}

	@Override
	public String toString() {
		// Credentials must never be logged
		return "%s://%s%s:%d".formatted(this.scheme, hasCredentials() ? "%s:***@".formatted(this.username) : "",
				this.host, this.port);
	}

}
