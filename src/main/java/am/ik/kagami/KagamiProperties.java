package am.ik.kagami;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import am.ik.kagami.rbac.RbacBuiltins;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.io.ApplicationResourceLoader;
import org.springframework.boot.ssl.pem.PemContent;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StreamUtils;

/**
 * Configuration properties for Kagami mirror server
 */
@ConfigurationProperties(prefix = "kagami")
public record KagamiProperties(@DefaultValue Storage storage, @DefaultValue Map<String, Repository> repositories,
		@Nullable Proxy proxy, @DefaultValue Jwt jwt, @DefaultValue Authentication authentication,
		@DefaultValue Rbac rbac) {

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		@Nullable private Storage storage;

		@Nullable private Map<String, Repository> repositories;

		@Nullable private Proxy proxy;

		@Nullable private Jwt jwt;

		@Nullable private Authentication authentication;

		@Nullable private Rbac rbac;

		private Builder() {
		}

		public Builder storage(Storage storage) {
			this.storage = storage;
			return this;
		}

		public Builder repositories(Map<String, Repository> repositories) {
			this.repositories = repositories;
			return this;
		}

		public Builder proxy(@Nullable Proxy proxy) {
			this.proxy = proxy;
			return this;
		}

		public Builder jwt(Jwt jwt) {
			this.jwt = jwt;
			return this;
		}

		public Builder authentication(Authentication authentication) {
			this.authentication = authentication;
			return this;
		}

		public Builder rbac(Rbac rbac) {
			this.rbac = rbac;
			return this;
		}

		public KagamiProperties build() {
			return new KagamiProperties(Objects.requireNonNull(this.storage, "storage is required"),
					Objects.requireNonNull(this.repositories, "repositories is required"), this.proxy,
					Objects.requireNonNull(this.jwt, "jwt is required"),
					Objects.requireNonNull(this.authentication, "authentication is required"),
					this.rbac == null ? Rbac.builder().build() : this.rbac);
		}

	}

	/**
	 * Storage backend settings.
	 *
	 * @param type the backend that holds the mirrored artifacts
	 * @param path the base directory of the {@link StorageType#LOCAL} backend
	 * @param s3 the {@link StorageType#S3} backend settings, required when {@code type}
	 * is {@code S3}
	 */
	public record Storage(@DefaultValue("local") StorageType type, @Nullable String path, @Nullable S3 s3) {

		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Amazon S3 or S3-compatible object storage settings. Endpoint, region and
		 * credentials are configured through {@code spring.cloud.aws.*}.
		 *
		 * @param bucket the bucket that holds the mirrored artifacts
		 * @param keyPrefix optional prefix prepended to every object key, in front of the
		 * repository id
		 */
		public record S3(String bucket, @Nullable String keyPrefix) {
		}

		public static final class Builder {

			private StorageType type = StorageType.LOCAL;

			@Nullable private String path;

			@Nullable private S3 s3;

			private Builder() {
			}

			public Builder type(StorageType type) {
				this.type = type;
				return this;
			}

			public Builder path(@Nullable String path) {
				this.path = path;
				return this;
			}

			public Builder s3(@Nullable S3 s3) {
				this.s3 = s3;
				return this;
			}

			public Storage build() {
				return new Storage(this.type, this.path, this.s3);
			}

		}

	}

	public enum StorageType {

		LOCAL, S3

	}

	/**
	 * A remote repository mirrored by Kagami.
	 *
	 * @param url the base URL of the remote repository
	 * @param username optional user name for Basic authentication against the remote
	 * repository
	 * @param password optional password for Basic authentication against the remote
	 * repository
	 * @param isPrivate whether the repository requires authentication to access
	 * @param priority the display priority; repositories with a higher priority are
	 * listed first on the web UI and in the generated configuration examples. Defaults to
	 * {@code 0}
	 * @param sigstore the sigstore attestation bundle settings
	 */
	public record Repository(String url, @Nullable String username, @Nullable String password,
			@DefaultValue("false") boolean isPrivate, @DefaultValue("0") int priority,
			@DefaultValue Sigstore sigstore) implements Comparable<Repository> {

		/**
		 * The natural ordering sorts by priority, higher first, so that a plain
		 * {@code sorted()} call lists repositories in display order.
		 */
		@Override
		public int compareTo(Repository other) {
			return Integer.compare(other.priority, this.priority);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			@Nullable private String url;

			@Nullable private String username;

			@Nullable private String password;

			private boolean isPrivate;

			private int priority;

			@Nullable private Sigstore sigstore;

			private Builder() {
			}

			public Builder url(String url) {
				this.url = url;
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

			public Builder isPrivate(boolean isPrivate) {
				this.isPrivate = isPrivate;
				return this;
			}

			public Builder priority(int priority) {
				this.priority = priority;
				return this;
			}

			public Repository build() {
				return new Repository(Objects.requireNonNull(this.url, "url is required"), this.username, this.password,
						this.isPrivate, this.priority,
						this.sigstore == null ? Sigstore.builder().build() : this.sigstore);
			}

			public Builder sigstore(@Nullable Sigstore sigstore) {
				this.sigstore = sigstore;
				return this;
			}

		}

	}

	/**
	 * Sigstore attestation bundle settings for a repository.
	 *
	 * @param enabled whether Kagami fetches sigstore attestation bundles distributed by
	 * the upstream repository as sidecar files of artifacts; defaults to {@code false}
	 * @param bundleSuffixes the suffixes of bundle files tried as
	 * {@code <artifact filename> + "." + suffix} against the upstream; defaults to
	 * {@code attestation.sigstore.json} (Tanzu Spring) and {@code sigstore.json} (Maven
	 * Central)
	 * @param publicKeyUrl the URL of the public key used to verify the bundles; used by
	 * bundle verification, not by proxying
	 */
	public record Sigstore(@DefaultValue("false") boolean enabled, @Nullable List<String> bundleSuffixes,
			@Nullable String publicKeyUrl) {

		public static final List<String> DEFAULT_BUNDLE_SUFFIXES = List.of("attestation.sigstore.json",
				"sigstore.json");

		public Sigstore {
			if (bundleSuffixes == null || bundleSuffixes.isEmpty()) {
				bundleSuffixes = DEFAULT_BUNDLE_SUFFIXES;
			}
			bundleSuffixes = List.copyOf(bundleSuffixes);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			private boolean enabled;

			@Nullable private List<String> bundleSuffixes;

			@Nullable private String publicKeyUrl;

			private Builder() {
			}

			public Builder enabled(boolean enabled) {
				this.enabled = enabled;
				return this;
			}

			public Builder bundleSuffixes(@Nullable List<String> bundleSuffixes) {
				this.bundleSuffixes = bundleSuffixes;
				return this;
			}

			public Builder publicKeyUrl(@Nullable String publicKeyUrl) {
				this.publicKeyUrl = publicKeyUrl;
				return this;
			}

			public Sigstore build() {
				return new Sigstore(this.enabled, this.bundleSuffixes, this.publicKeyUrl);
			}

		}

	}

	/**
	 * HTTP proxy settings for outgoing connections to remote repositories.
	 *
	 * @param url proxy used for {@code http} repositories and, unless {@code httpsUrl} is
	 * set, for {@code https} repositories as well
	 * @param httpsUrl proxy used for {@code https} repositories
	 * @param username user name for proxy authentication, unless the credentials are
	 * embedded in the proxy URL
	 * @param password password for proxy authentication, unless the credentials are
	 * embedded in the proxy URL
	 * @param nonProxyHosts hosts that must be reached without going through the proxy
	 */
	public record Proxy(@Nullable String url, @Nullable String httpsUrl, @Nullable String username,
			@Nullable String password, @DefaultValue List<String> nonProxyHosts) {

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			@Nullable private String url;

			@Nullable private String httpsUrl;

			@Nullable private String username;

			@Nullable private String password;

			private List<String> nonProxyHosts = List.of();

			private Builder() {
			}

			public Builder url(@Nullable String url) {
				this.url = url;
				return this;
			}

			public Builder httpsUrl(@Nullable String httpsUrl) {
				this.httpsUrl = httpsUrl;
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

			public Builder nonProxyHosts(List<String> nonProxyHosts) {
				this.nonProxyHosts = nonProxyHosts;
				return this;
			}

			public Proxy build() {
				return new Proxy(this.url, this.httpsUrl, this.username, this.password, this.nonProxyHosts);
			}

		}

	}

	public static class Jwt {

		/**
		 * The private key bundled with Kagami. Tokens signed with it are not secure
		 * because the key pair ships with the application.
		 */
		public static final String DEFAULT_PRIVATE_KEY = "classpath:kagami-private.pem";

		/** The public key bundled with Kagami. */
		public static final String DEFAULT_PUBLIC_KEY = "classpath:kagami-public.pem";

		private final @Nullable String publicKeyLocation;

		private final @Nullable String privateKeyLocation;

		private final @Nullable RSAPublicKey publicKey;

		private final @Nullable RSAPrivateKey privateKey;

		// to support `base64:` prefix
		private static final ResourceLoader resourceLoader = ApplicationResourceLoader.get();

		public Jwt(@Nullable String publicKey, @Nullable String privateKey) {
			this.publicKeyLocation = publicKey;
			this.privateKeyLocation = privateKey;
			this.publicKey = publicKey == null ? null : resourceToPublicKey(resourceLoader.getResource(publicKey));
			this.privateKey = privateKey == null ? null : resourceToPrivateKey(resourceLoader.getResource(privateKey));
		}

		/**
		 * Whether the built-in (bundled) PEM keys are in use. {@code true} when either
		 * key location still points at the bundled key pair.
		 */
		public boolean defaultKeys() {
			return DEFAULT_PUBLIC_KEY.equals(this.publicKeyLocation)
					|| DEFAULT_PRIVATE_KEY.equals(this.privateKeyLocation);
		}

		public RSAPublicKey publicKey() {
			return Objects.requireNonNull(this.publicKey, "'kagami.jwt.public-key' is not configured");
		}

		public RSAPrivateKey privateKey() {
			return Objects.requireNonNull(this.privateKey, "'kagami.jwt.private-key' is not configured");
		}

		public String keyId() {
			byte[] publicKeyDERBytes = publicKey().getEncoded();
			try {
				MessageDigest hasher = MessageDigest.getInstance("SHA-256");
				byte[] publicKeyDERHash = hasher.digest(publicKeyDERBytes);
				return Base64.getUrlEncoder().withoutPadding().encodeToString(publicKeyDERHash);
			}
			catch (NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
		}

		static RSAPublicKey resourceToPublicKey(Resource resource) {
			try (InputStream stream = resource.getInputStream()) {
				byte[] content = Base64.getDecoder()
					.decode(StreamUtils.copyToString(stream, StandardCharsets.UTF_8)
						.replace("-----BEGIN PUBLIC KEY-----", "")
						.replace("-----END PUBLIC KEY-----", "")
						.replace("\n", ""));
				X509EncodedKeySpec spec = new X509EncodedKeySpec(content);
				return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
			}
			catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
		}

		static RSAPrivateKey resourceToPrivateKey(Resource resource) {
			try (InputStream stream = resource.getInputStream()) {
				PemContent pemContent = Objects.requireNonNull(
						PemContent.of(StreamUtils.copyToString(stream, StandardCharsets.UTF_8)),
						"No PEM content found in " + resource);
				return (RSAPrivateKey) Objects.requireNonNull(pemContent.getPrivateKey(),
						"No private key found in " + resource);
			}
			catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

	}

	public record Authentication(@DefaultValue("simple") AuthenticationType type,
			@DefaultValue(".*") List<Pattern> allowedNamePatterns) {
	}

	public enum AuthenticationType {

		SIMPLE, OIDC

	}

	/**
	 * Group-based RBAC settings. A group is a named set of authorities and users are
	 * mapped to groups through properties; a group never appears in authorization rules
	 * itself, only the authorities it expands into do. The authority vocabulary reuses
	 * the JWT scope vocabulary ({@code artifacts:read}, {@code artifacts:delete},
	 * {@code artifacts:admin}), so scope-based and group-based authorization share one
	 * namespace.
	 * <p>
	 * Group names containing {@code @} or {@code .} must be configured with the bracket
	 * notation (e.g. {@code kagami.rbac.mappings.users[taro@example.com]=editors}) so
	 * that Spring Boot relaxed binding does not mangle the key.
	 *
	 * @param defaultGroup the group applied to users absent from every mapping
	 * @param groups the group definitions: group name to the authorities it expands into;
	 * merged over the built-in groups ({@code administrators}, {@code editors},
	 * {@code viewers}) so that an entry with the same name overrides the built-in one
	 * @param mappings the user to groups and IdP groups claim to groups mappings
	 * @param groupsClaim the name of the OIDC claim that carries the IdP group
	 * memberships, bound from {@code kagami.rbac.groups-claim}; defaults to
	 * {@code groups}
	 */
	public record Rbac(@DefaultValue(RbacBuiltins.DEFAULT_GROUP) String defaultGroup,
			@DefaultValue Map<String, List<String>> groups, @DefaultValue Mappings mappings,
			@DefaultValue(RbacBuiltins.DEFAULT_GROUPS_CLAIM) String groupsClaim) {

		public static Builder builder() {
			return new Builder();
		}

		public Rbac {
			Map<String, List<String>> merged = new LinkedHashMap<>(RbacBuiltins.BUILT_IN_GROUPS);
			merged.putAll(groups == null ? Map.of() : groups);
			groups = Map.copyOf(merged);
			mappings = mappings == null ? new Mappings(Map.of(), Map.of()) : mappings;
			groupsClaim = groupsClaim == null ? RbacBuiltins.DEFAULT_GROUPS_CLAIM : groupsClaim;
		}

		public static final class Builder {

			private String defaultGroup = RbacBuiltins.DEFAULT_GROUP;

			private Map<String, List<String>> groups = Map.of();

			private Mappings mappings = new Mappings(Map.of(), Map.of());

			private String groupsClaim = RbacBuiltins.DEFAULT_GROUPS_CLAIM;

			private Builder() {
			}

			public Builder defaultGroup(String defaultGroup) {
				this.defaultGroup = defaultGroup;
				return this;
			}

			public Builder groups(Map<String, List<String>> groups) {
				this.groups = groups;
				return this;
			}

			public Builder mappings(Mappings mappings) {
				this.mappings = mappings;
				return this;
			}

			public Builder groupsClaim(String groupsClaim) {
				this.groupsClaim = groupsClaim;
				return this;
			}

			public Rbac build() {
				return new Rbac(this.defaultGroup, this.groups, this.mappings, this.groupsClaim);
			}

		}

	}

	/**
	 * The user to groups and IdP groups claim to groups mappings.
	 *
	 * @param users the username to groups mapping, common to simple and OIDC
	 * authentication, bound from {@code kagami.rbac.mappings.users.*}
	 * @param groups the IdP groups claim value to Kagami groups translation for OIDC
	 * authentication, bound from {@code kagami.rbac.mappings.groups.*}
	 */
	public record Mappings(@DefaultValue Map<String, List<String>> users,
			@DefaultValue Map<String, List<String>> groups) {
	}

}