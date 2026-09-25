package am.ik.kagami.mockoidc;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.ssl.pem.PemContent;
import org.springframework.util.StreamUtils;

/**
 * Minimal OIDC provider backed by the JDK {@link HttpServer} for browser E2E tests. It
 * implements just enough of the authorization code flow for Spring Security's
 * {@code oauth2Login}: discovery, authorization (immediate consent redirect), token
 * (issuing an RS256-signed id token) and JWKS.
 * <p>
 * The id token is signed with the same RSA key pair the application ships with
 * ({@code kagami-private.pem}), so the JWKS endpoint publishes the matching public key.
 */
public class MockOidcServer implements AutoCloseable {

	private static final Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final HttpServer server;

	private final int port;

	private final RSAPrivateKey privateKey;

	private final String keyId;

	private String subject = "user@example.com";

	private String email = "user@example.com";

	private volatile java.util.@org.jspecify.annotations.Nullable List<String> groups;

	public MockOidcServer(int port) {
		try {
			this.server = HttpServer.create(new InetSocketAddress(port), 0);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		// An ephemeral port (0) resolves to the actually bound port, which avoids the
		// race of probing a free port first and binding it later
		this.port = this.server.getAddress().getPort();
		this.privateKey = loadPrivateKey();
		this.keyId = keyId(this.privateKey);
		this.server.createContext("/.well-known/openid-configuration", exchange -> json(exchange, discovery()));
		this.server.createContext("/oauth2/jwks", exchange -> json(exchange, jwks()));
		this.server.createContext("/oauth2/authorize", this::authorize);
		this.server.createContext("/oauth2/token", this::token);
		this.server.setExecutor(Executors.newSingleThreadExecutor());
	}

	public void run() {
		this.server.start();
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

	public int port() {
		return this.port;
	}

	public String issuer() {
		return "http://127.0.0.1:" + this.port;
	}

	/**
	 * Overrides the claims of the id token issued by the next login.
	 */
	public MockOidcServer subject(String subject) {
		this.subject = subject;
		return this;
	}

	public MockOidcServer email(String email) {
		this.email = email;
		return this;
	}

	/**
	 * Overrides the groups claim of the id token issued by the next login.
	 */
	public MockOidcServer groups(java.util.List<String> groups) {
		this.groups = groups;
		return this;
	}

	private String discovery() {
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("issuer", issuer());
		claims.put("authorization_endpoint", issuer() + "/oauth2/authorize");
		claims.put("token_endpoint", issuer() + "/oauth2/token");
		claims.put("jwks_uri", issuer() + "/oauth2/jwks");
		claims.put("response_types_supported", java.util.List.of("code"));
		claims.put("subject_types_supported", java.util.List.of("public"));
		claims.put("id_token_signing_alg_values_supported", java.util.List.of("RS256"));
		claims.put("scopes_supported", java.util.List.of("openid", "email"));
		claims.put("token_endpoint_auth_methods_supported", java.util.List.of("client_secret_basic"));
		return toJson(claims);
	}

	private String jwks() {
		var crtKey = (java.security.interfaces.RSAPrivateCrtKey) this.privateKey;
		String n = URL_ENCODER.encodeToString(toUnsignedByteArray(crtKey.getModulus()));
		String e = URL_ENCODER.encodeToString(toUnsignedByteArray(crtKey.getPublicExponent()));
		return toJson(Map.of("keys", java.util.List
			.of(Map.of("kty", "RSA", "kid", this.keyId, "alg", "RS256", "use", "sig", "n", n, "e", e))));
	}

	/**
	 * Simulates an immediate user consent: redirects straight back to the client's
	 * redirect URI with an authorization code and the state echoed back.
	 */
	private volatile @org.jspecify.annotations.Nullable String nonce;

	private void authorize(HttpExchange exchange) throws IOException {
		var query = parseQuery(exchange.getRequestURI().getRawQuery());
		String redirectUri = query.get("redirect_uri");
		String state = query.getOrDefault("state", "");
		// Remember the nonce; Spring Security validates the id token claim against it
		// itself, it does not send the nonce to the token endpoint
		this.nonce = query.get("nonce");
		exchange.getResponseHeaders().set("Location", redirectUri + "?code=mock-code&state=" + state);
		exchange.sendResponseHeaders(302, -1);
	}

	private void token(HttpExchange exchange) throws IOException {
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("iss", issuer());
		claims.put("sub", this.subject);
		claims.put("aud", java.util.List.of("kagami"));
		claims.put("email", this.email);
		if (this.groups != null) {
			claims.put("groups", this.groups);
		}
		if (this.nonce != null) {
			claims.put("nonce", this.nonce);
		}
		long now = System.currentTimeMillis() / 1000;
		claims.put("iat", now);
		claims.put("exp", now + 300);
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("access_token", "mock-access-token");
		response.put("token_type", "Bearer");
		response.put("expires_in", 300);
		response.put("id_token", idToken(claims));
		json(exchange, toJson(response));
	}

	private String idToken(Map<String, Object> claims) {
		String header = URL_ENCODER.encodeToString(
				toJson(Map.of("alg", "RS256", "kid", this.keyId, "typ", "JWT")).getBytes(StandardCharsets.UTF_8));
		String payload = URL_ENCODER.encodeToString(toJson(claims).getBytes(StandardCharsets.UTF_8));
		String signingInput = header + "." + payload;
		try {
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(this.privateKey);
			signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
			return signingInput + "." + URL_ENCODER.encodeToString(signature.sign());
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to sign id token", e);
		}
	}

	private static void json(HttpExchange exchange, String body) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	private static Map<String, String> parseQuery(String query) {
		Map<String, String> params = new LinkedHashMap<>();
		if (query != null && !query.isEmpty()) {
			for (String pair : query.split("&")) {
				String[] keyValue = pair.split("=", 2);
				if (keyValue.length == 2) {
					params.put(keyValue[0], keyValue[1]);
				}
			}
		}
		return params;
	}

	private static String toJson(Map<String, Object> map) {
		StringBuilder builder = new StringBuilder("{");
		BiFunction<String, Object, String> entry = (key, value) -> quote(key) + ":" + toJsonValue(value);
		builder.append(map.entrySet()
			.stream()
			.map(e -> entry.apply(e.getKey(), e.getValue()))
			.reduce((a, b) -> a + "," + b)
			.orElse(""));
		return builder.append("}").toString();
	}

	private static String toJsonValue(Object value) {
		if (value instanceof String string) {
			return quote(string);
		}
		if (value instanceof Map<?, ?> map) {
			StringBuilder builder = new StringBuilder("{");
			builder.append(map.entrySet()
				.stream()
				.map(e -> quote(String.valueOf(e.getKey())) + ":" + toJsonValue(e.getValue()))
				.reduce((a, b) -> a + "," + b)
				.orElse(""));
			return builder.append("}").toString();
		}
		if (value instanceof java.util.List<?> list) {
			StringBuilder builder = new StringBuilder("[");
			builder.append(list.stream().map(MockOidcServer::toJsonValue).reduce((a, b) -> a + "," + b).orElse(""));
			return builder.append("]").toString();
		}
		return String.valueOf(value);
	}

	private static String quote(String value) {
		return "\"" + value + "\"";
	}

	private static RSAPrivateKey loadPrivateKey() {
		try (var stream = MockOidcServer.class.getResourceAsStream("/kagami-private.pem")) {
			String pem = StreamUtils.copyToString(stream, StandardCharsets.UTF_8);
			PemContent pemContent = PemContent.of(pem);
			PrivateKey privateKey = java.util.Objects.requireNonNull(pemContent.getPrivateKey(), "No private key");
			return (RSAPrivateKey) privateKey;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String keyId(RSAPrivateKey privateKey) {
		try {
			byte[] modulus = toUnsignedByteArray(privateKey.getModulus());
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			return URL_ENCODER.encodeToString(digest.digest(modulus));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] toUnsignedByteArray(java.math.BigInteger value) {
		byte[] bytes = value.toByteArray();
		// Drop the leading zero byte that BigInteger prepends for positive values
		if (bytes.length > 1 && bytes[0] == 0) {
			byte[] trimmed = new byte[bytes.length - 1];
			System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
			return trimmed;
		}
		return bytes;
	}

}
