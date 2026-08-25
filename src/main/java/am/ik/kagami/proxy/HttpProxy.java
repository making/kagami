package am.ik.kagami.proxy;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
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
public record HttpProxy(String scheme, String host, int port, @Nullable String username, @Nullable String password) {

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		@Nullable private String scheme;

		@Nullable private String host;

		private int port;

		@Nullable private String username;

		@Nullable private String password;

		private Builder() {
		}

		public Builder scheme(String scheme) {
			this.scheme = scheme;
			return this;
		}

		public Builder host(String host) {
			this.host = host;
			return this;
		}

		public Builder port(int port) {
			this.port = port;
			return this;
		}

		public Builder username(@Nullable String username) {
			this.username = username;
			return this;
		}

		public Builder password(@Nullable String password) {
			this.password = password;
			return this;
		}

		public HttpProxy build() {
			return new HttpProxy(Objects.requireNonNull(this.scheme, "scheme is required"),
					Objects.requireNonNull(this.host, "host is required"), this.port, this.username, this.password);
		}

	}

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
