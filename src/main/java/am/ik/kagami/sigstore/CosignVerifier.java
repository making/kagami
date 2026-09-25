package am.ik.kagami.sigstore;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import am.ik.kagami.KagamiProperties;
import am.ik.kagami.KagamiProperties.Verification;
import am.ik.kagami.storage.ArtifactLocation;
import am.ik.kagami.storage.StorageEntry;
import am.ik.kagami.storage.StorageService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.io.ApplicationResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

/**
 * Verifies a stored artifact against its sigstore attestation bundle by running the
 * external {@code cosign} binary.
 * <p>
 * The trust anchor never comes from user input: it is pinned per repository, either a
 * public key ({@link Verification#KEY}, {@code --key} with
 * {@code --insecure-ignore-tlog}) or identity/issuer constraints over the certificate
 * embedded in the bundle ({@link Verification#KEYLESS}, full transparency log
 * verification). Verification only runs when both the artifact and the bundle are present
 * in the storage, so garbage collected artifacts are reported instead of failing
 * obscurely.
 */
@Service
public class CosignVerifier {

	private static final Logger logger = LoggerFactory.getLogger(CosignVerifier.class);

	/** The default cosign path, resolved through the {@code PATH}. */
	static final String DEFAULT_COSIGN = "cosign";

	/**
	 * The cosign binary embedded in the application jar by the {@code embedded-cosign}
	 * Maven profile.
	 */
	static final String EMBEDDED_COSIGN = "classpath:cosign/cosign";

	private final StorageService storageService;

	private final KagamiProperties properties;

	private final RestClient restClient;

	private final AsyncTaskExecutor taskExecutor;

	private final ResourceLoader resourceLoader = ApplicationResourceLoader.get();

	private final ConcurrentHashMap<String, String> publicKeyCache = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, Path> cosignBinaryCache = new ConcurrentHashMap<>();

	public CosignVerifier(StorageService storageService, KagamiProperties properties,
			RestClient.Builder restClientBuilder, AsyncTaskExecutor taskExecutor) {
		this.storageService = storageService;
		this.properties = properties;
		// The RestClient is already routed through the proxy by the auto-configured
		// request factory
		this.restClient = restClientBuilder.build();
		// The Spring managed executor shared with the rest of the application
		this.taskExecutor = taskExecutor;
	}

	/**
	 * The outcome of a verification attempt.
	 *
	 * @param verified whether cosign accepted the bundle for the artifact
	 * @param exitCode the exit code of the cosign process, absent when cosign never ran
	 * @param stdout the standard output of cosign, absent when cosign never ran
	 * @param stderr the standard error of cosign, absent when cosign never ran
	 * @param error the reason the verification could not be performed at all (missing
	 * files, missing configuration, cosign not installed, timeout), absent when cosign
	 * ran
	 */
	public record VerificationResult(boolean verified, @Nullable Integer exitCode, @Nullable String stdout,
			@Nullable String stderr, @Nullable String error) {

		static VerificationResult of(int exitCode, String stdout, String stderr) {
			return new VerificationResult(exitCode == 0, exitCode, stdout.strip(), stderr.strip(), null);
		}

		static VerificationResult error(String message) {
			return new VerificationResult(false, null, null, null, message);
		}
	}

	/**
	 * Verify the artifact at {@code artifactPath} against the bundle at
	 * {@code bundlePath}, both within the repository.
	 */
	public VerificationResult verify(String repositoryId, String artifactPath, String bundlePath) {
		KagamiProperties.Repository repoConfig = this.properties.repositories().get(repositoryId);
		if (repoConfig == null) {
			return VerificationResult.error("Repository not found: " + repositoryId);
		}
		try {
			Path artifactFile = tempCopy(new ArtifactLocation(repositoryId, artifactPath), "artifact");
			if (artifactFile == null) {
				return VerificationResult
					.error("Artifact not found in storage (it may have been garbage collected): " + artifactPath);
			}
			Path bundleFile = tempCopy(new ArtifactLocation(repositoryId, bundlePath), "bundle");
			if (bundleFile == null) {
				deleteQuietly(artifactFile);
				return VerificationResult.error("Bundle not found in storage: " + bundlePath);
			}
			try {
				String digest = sha256(artifactFile);
				List<String> command = command(repoConfig, repositoryId, bundleFile, digest);
				return run(command);
			}
			finally {
				deleteQuietly(artifactFile);
				deleteQuietly(bundleFile);
			}
		}
		catch (IOException e) {
			logger.warn("Failed to prepare cosign verification of {}/{}", repositoryId, artifactPath, e);
			return VerificationResult.error("Failed to prepare verification: " + e);
		}
		catch (IllegalArgumentException e) {
			return VerificationResult.error("Verification is not possible: " + e.getMessage());
		}
	}

	// ---------- command assembly ----------

	private List<String> command(KagamiProperties.Repository repoConfig, String repositoryId, Path bundleFile,
			String digest) throws IOException {
		List<String> command = new ArrayList<>();
		command.add(cosignExecutable(this.properties.sigstore().cosignPath()).toString());
		command.add("verify-blob-attestation");
		command.add("--bundle");
		command.add(bundleFile.toString());
		command.add("--digest");
		command.add(digest);
		command.add("--digestAlg");
		command.add("sha256");
		command.add("--type");
		command.add(repoConfig.sigstore().attestationType());
		List<String> flags = verificationFlags(repoConfig.sigstore(), repositoryId);
		// In the executed command the public key is handed over as a local temporary file
		if (repoConfig.sigstore().verification() == KagamiProperties.Verification.KEY) {
			int keyFlag = flags.indexOf("--key");
			// verificationFlags has already rejected a missing public key URL
			flags.set(keyFlag + 1,
					publicKeyFile(Objects.requireNonNull(repoConfig.sigstore().publicKeyUrl())).toString());
		}
		command.addAll(flags);
		return command;
	}

	/**
	 * The inputs a user needs to reproduce the verification outside Kagami: the artifact
	 * and the bundle under their downloaded file names, the pinned public key under its
	 * download file name, and the digest of the stored artifact.
	 */
	public record VerificationInputs(String artifactFile, String bundleFile, @Nullable String digest,
			@Nullable String keyFile) {

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			@Nullable private String artifactFile;

			@Nullable private String bundleFile;

			@Nullable private String digest;

			@Nullable private String keyFile;

			private Builder() {
			}

			public Builder artifactFile(String artifactFile) {
				this.artifactFile = artifactFile;
				return this;
			}

			public Builder bundleFile(String bundleFile) {
				this.bundleFile = bundleFile;
				return this;
			}

			public Builder digest(@Nullable String digest) {
				this.digest = digest;
				return this;
			}

			public Builder keyFile(@Nullable String keyFile) {
				this.keyFile = keyFile;
				return this;
			}

			public VerificationInputs build() {
				return new VerificationInputs(Objects.requireNonNull(this.artifactFile, "artifactFile is required"),
						Objects.requireNonNull(this.bundleFile, "bundleFile is required"), this.digest, this.keyFile);
			}

		}

	}

	/**
	 * An equivalent command line for verifying the artifact against the bundle with a
	 * locally installed {@code cosign}, or {@code null} when the repository is not
	 * configured for sigstore verification.
	 */
	public @Nullable String commandLine(String repositoryId, VerificationInputs inputs) {
		KagamiProperties.Repository repoConfig = this.properties.repositories().get(repositoryId);
		if (repoConfig == null) {
			return null;
		}
		KagamiProperties.Sigstore sigstore = repoConfig.sigstore();
		List<String> command = new ArrayList<>();
		command.add(DEFAULT_COSIGN);
		command.add("verify-blob-attestation");
		command.add(inputs.artifactFile());
		command.add("--bundle");
		command.add(inputs.bundleFile());
		if (inputs.digest() != null && !inputs.digest().isEmpty()) {
			command.add("--digest");
			command.add(inputs.digest());
			command.add("--digestAlg");
			command.add("sha256");
		}
		command.add("--type");
		command.add(sigstore.attestationType());
		try {
			command.addAll(verificationFlags(sigstore, repositoryId));
		}
		catch (IllegalArgumentException e) {
			// The verification itself would fail with the same message; showing a broken
			// command would not help
			return null;
		}
		// The key is referenced under its download file name, not the server side
		// location
		if (sigstore.verification() == KagamiProperties.Verification.KEY && inputs.keyFile() != null
				&& !inputs.keyFile().isEmpty()) {
			int keyFlag = command.indexOf("--key");
			command.set(keyFlag + 1, inputs.keyFile());
		}
		return String.join(" ", shellQuoted(command));
	}

	/**
	 * Renders the raw command arguments as they would be typed in a shell: the public key
	 * location loses its resource scheme and values the shell would interpret are single
	 * quoted.
	 */
	private static List<String> shellQuoted(List<String> command) {
		List<String> rendered = new ArrayList<>(command.size());
		for (int i = 0; i < command.size(); i++) {
			String argument = command.get(i);
			String previous = i > 0 ? command.get(i - 1) : "";
			switch (previous) {
				case "--certificate-identity-regexp", "--certificate-oidc-issuer" -> rendered.add(shellQuote(argument));
				default -> rendered.add(shellQuote(argument));
			}
		}
		return rendered;
	}

	/**
	 * The URL of the public key the repository pins for key verification, or {@code null}
	 * when the repository does not verify with a key.
	 */
	public @Nullable String publicKeyUrl(String repositoryId) {
		KagamiProperties.Repository repoConfig = this.properties.repositories().get(repositoryId);
		if (repoConfig == null || repoConfig.sigstore().verification() != KagamiProperties.Verification.KEY) {
			return null;
		}
		String publicKeyUrl = repoConfig.sigstore().publicKeyUrl();
		return publicKeyUrl == null || publicKeyUrl.isEmpty() ? null : publicKeyUrl;
	}

	/**
	 * The pinned public key of a key verification repository as a locally readable
	 * resource, or {@code null} when the repository does not verify with a key, the key
	 * is a remote URL (the caller redirects to it instead) or the resource does not
	 * exist.
	 */
	public @Nullable Resource publicKeyResource(String repositoryId) {
		String publicKeyUrl = publicKeyUrl(repositoryId);
		if (publicKeyUrl == null || publicKeyUrl.startsWith("http://") || publicKeyUrl.startsWith("https://")) {
			return null;
		}
		Resource resource = this.resourceLoader.getResource(publicKeyUrl);
		return resource.exists() ? resource : null;
	}

	/**
	 * The trust anchor flags shared by the executed command and the rendered equivalent
	 * command line.
	 */
	private static List<String> verificationFlags(KagamiProperties.Sigstore sigstore, String repositoryId) {
		List<String> flags = new ArrayList<>();
		switch (sigstore.verification()) {
			case KEY -> {
				if (sigstore.publicKeyUrl() == null || sigstore.publicKeyUrl().isEmpty()) {
					throw new IllegalArgumentException(
							"'kagami.repositories.%s.sigstore.public-key-url' is not configured"
								.formatted(repositoryId));
				}
				flags.add("--key");
				flags.add(sigstore.publicKeyUrl());
				flags.add("--insecure-ignore-tlog=true");
			}
			case KEYLESS -> {
				if (sigstore.certificateIdentityRegExp() == null || sigstore.certificateIdentityRegExp().isEmpty()) {
					throw new IllegalArgumentException("'kagami.repositories.%s.sigstore.certificate-identity-regexp' "
							+ "is required for keyless verification".formatted(repositoryId));
				}
				flags.add("--certificate-identity-regexp");
				flags.add(sigstore.certificateIdentityRegExp());
				if (sigstore.certificateOidcIssuer() != null && !sigstore.certificateOidcIssuer().isEmpty()) {
					flags.add("--certificate-oidc-issuer");
					flags.add(sigstore.certificateOidcIssuer());
				}
			}
		}
		return flags;
	}

	/** Single quotes the value when it contains characters the shell would interpret. */
	private static String shellQuote(String value) {
		if (value.matches("[A-Za-z0-9_@%+=:,./-]+")) {
			return value;
		}
		return "'" + value.replace("'", "'\\''") + "'";
	}

	/**
	 * Resolves the public key to a local file: {@code classpath:} and {@code file:}
	 * locations are read directly, anything else is fetched over HTTP and cached in
	 * memory.
	 */
	private Path publicKeyFile(String publicKeyUrl) throws IOException {
		if (publicKeyUrl.startsWith("classpath:") || publicKeyUrl.startsWith("file:")) {
			Resource resource = this.resourceLoader.getResource(publicKeyUrl);
			try (InputStream inputStream = resource.getInputStream()) {
				return tempFile(inputStream.readAllBytes(), "kagami-public-key");
			}
		}
		String pem = this.publicKeyCache.computeIfAbsent(publicKeyUrl, url -> {
			logger.info("Fetching sigstore public key from {}", url);
			return this.restClient.get().uri(url).retrieve().body(String.class);
		});
		if (pem == null || pem.isEmpty()) {
			throw new IOException("Failed to fetch public key from " + publicKeyUrl);
		}
		return tempFile(pem.getBytes(StandardCharsets.UTF_8), "kagami-public-key");
	}

	/**
	 * Resolves the cosign binary to a local executable: {@code classpath:} locations (the
	 * binary embedded in the application jar by the {@code embedded-cosign} Maven
	 * profile) are extracted once to a temporary executable file, anything else is used
	 * as a plain path and resolved through the {@code PATH}.
	 */
	private Path cosignExecutable(String cosignPath) throws IOException {
		if (!cosignPath.startsWith("classpath:") && DEFAULT_COSIGN.equals(cosignPath)
				&& this.resourceLoader.getResource(EMBEDDED_COSIGN).exists()) {
			logger.debug("Using the cosign binary embedded in the application jar");
			cosignPath = EMBEDDED_COSIGN;
		}
		if (!cosignPath.startsWith("classpath:")) {
			return Path.of(cosignPath);
		}
		try {
			return this.cosignBinaryCache.computeIfAbsent(cosignPath, path -> {
				logger.info("Extracting the embedded cosign binary from {}", path);
				try (InputStream inputStream = this.resourceLoader.getResource(path).getInputStream()) {
					Path file = Files.createTempFile("kagami-cosign", null);
					Files.write(file, inputStream.readAllBytes());
					Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
					file.toFile().deleteOnExit();
					return file;
				}
				catch (IOException e) {
					throw new UncheckedIOException("Failed to extract cosign from " + path, e);
				}
			});
		}
		catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	// ---------- process execution ----------

	private VerificationResult run(List<String> command) {
		Process process;
		try {
			process = new ProcessBuilder(command).start();
		}
		catch (IOException e) {
			logger.warn("Failed to launch cosign: {}", e.getMessage());
			return VerificationResult.error("Failed to launch cosign (is it installed?): " + e);
		}
		CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> readStream(process.getInputStream()),
				this.taskExecutor);
		CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream()),
				this.taskExecutor);
		Duration timeout = this.properties.sigstore().timeout();
		try {
			if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				return VerificationResult.error("cosign timed out after " + timeout.getSeconds() + "s");
			}
			return VerificationResult.of(process.exitValue(), stdout.join(), stderr.join());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			return VerificationResult.error("Verification interrupted");
		}
	}

	private static String readStream(InputStream stream) {
		try {
			return StreamUtils.copyToString(stream, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			return "";
		}
	}

	// ---------- helpers ----------

	/**
	 * Copies a stored file into a temporary file, which works uniformly for every storage
	 * backend and isolates cosign from the storage.
	 * @return the temporary file path, or {@code null} when nothing is stored at the
	 * location
	 */
	private @Nullable Path tempCopy(ArtifactLocation location, String purpose) throws IOException {
		if (this.storageService.stat(location).filter(StorageEntry::isFile).isEmpty()) {
			return null;
		}
		Resource resource = this.storageService.retrieve(location).orElseThrow();
		try (InputStream inputStream = resource.getInputStream()) {
			return tempFile(inputStream.readAllBytes(), "kagami-" + purpose);
		}
	}

	private static Path tempFile(byte[] content, String prefix) throws IOException {
		Path file = Files.createTempFile(prefix, null);
		Files.write(file, content);
		return file;
	}

	private static String sha256(Path file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		try (InputStream inputStream = Files.newInputStream(file)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = inputStream.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException e) {
			logger.debug("Failed to delete temporary file {}", file, e);
		}
	}

}
