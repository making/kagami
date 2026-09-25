package am.ik.kagami.sigstore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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

	private final StorageService storageService;

	private final KagamiProperties properties;

	private final RestClient restClient;

	private final AsyncTaskExecutor taskExecutor;

	private final ResourceLoader resourceLoader = ApplicationResourceLoader.get();

	private final ConcurrentHashMap<String, String> publicKeyCache = new ConcurrentHashMap<>();

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
		KagamiProperties.Sigstore sigstore = repoConfig.sigstore();
		List<String> command = new ArrayList<>();
		command.add(this.properties.sigstore().cosignPath());
		command.add("verify-blob-attestation");
		command.add("--bundle");
		command.add(bundleFile.toString());
		command.add("--digest");
		command.add(digest);
		command.add("--digestAlg");
		command.add("sha256");
		command.add("--type");
		command.add(sigstore.attestationType());
		switch (sigstore.verification()) {
			case KEY -> {
				if (sigstore.publicKeyUrl() == null || sigstore.publicKeyUrl().isEmpty()) {
					throw new IllegalArgumentException(
							"'kagami.repositories.%s.sigstore.public-key-url' is not configured"
								.formatted(repositoryId));
				}
				command.add("--key");
				command.add(publicKeyFile(sigstore.publicKeyUrl()).toString());
				command.add("--insecure-ignore-tlog=true");
			}
			case KEYLESS -> {
				if (sigstore.certificateIdentityRegExp() == null || sigstore.certificateIdentityRegExp().isEmpty()) {
					throw new IllegalArgumentException("'kagami.repositories.%s.sigstore.certificate-identity-regexp' "
							+ "is required for keyless verification".formatted(repositoryId));
				}
				command.add("--certificate-identity-regexp");
				command.add(sigstore.certificateIdentityRegExp());
				if (sigstore.certificateOidcIssuer() != null && !sigstore.certificateOidcIssuer().isEmpty()) {
					command.add("--certificate-oidc-issuer");
					command.add(sigstore.certificateOidcIssuer());
				}
			}
		}
		return command;
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
