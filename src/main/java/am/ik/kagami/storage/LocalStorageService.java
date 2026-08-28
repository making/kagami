package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Local file system implementation of StorageService
 */
@Service
public class LocalStorageService implements StorageService {

	private final Path basePath;

	public LocalStorageService(KagamiProperties properties) {
		this.basePath = Path.of(properties.storage().path()).toAbsolutePath().normalize();
		try {
			Files.createDirectories(this.basePath);
		}
		catch (IOException e) {
			throw new IllegalStateException("Failed to create storage directory: " + this.basePath, e);
		}
	}

	@Override
	public void store(ArtifactLocation location, InputStream inputStream) throws IOException {
		Path targetPath = resolvePath(location);
		Path directory = targetPath.getParent();
		Files.createDirectories(directory);
		// Write to a temporary file in the same directory and swap it in atomically, so
		// that a concurrent request never reads a half-written artifact and never has the
		// file it is streaming truncated underneath it
		Path temporaryPath = Files.createTempFile(directory, "." + targetPath.getFileName(), ".tmp");
		try {
			Files.copy(inputStream, temporaryPath, StandardCopyOption.REPLACE_EXISTING);
			moveIntoPlace(temporaryPath, targetPath);
		}
		finally {
			Files.deleteIfExists(temporaryPath);
		}
	}

	/**
	 * Move the fully written temporary file onto the target path, replacing any artifact
	 * stored earlier.
	 * @param temporaryPath the temporary file holding the complete artifact
	 * @param targetPath the final path of the artifact
	 * @throws IOException if an I/O error occurs
	 */
	private void moveIntoPlace(Path temporaryPath, Path targetPath) throws IOException {
		try {
			Files.move(temporaryPath, targetPath, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException e) {
			// Fall back for file systems that cannot rename atomically
			Files.move(temporaryPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	@Override
	public Optional<Resource> retrieve(ArtifactLocation location) {
		Path targetPath = resolvePath(location);
		if (Files.exists(targetPath) && Files.isRegularFile(targetPath)) {
			return Optional.of(new PathResource(targetPath));
		}
		return Optional.empty();
	}

	@Override
	public boolean delete(ArtifactLocation location) throws IOException {
		Path targetPath = resolvePath(location);

		if (!Files.exists(targetPath)) {
			return false;
		}

		if (Files.isDirectory(targetPath)) {
			// Delete directory recursively
			try (Stream<Path> walk = Files.walk(targetPath)) {
				walk.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.delete(path);
					}
					catch (IOException e) {
						throw new RuntimeException("Failed to delete: " + path, e);
					}
				});
			}
		}
		else {
			// Delete single file
			Files.delete(targetPath);
		}

		return true;
	}

	private Path resolvePath(ArtifactLocation location) {
		String artifactPath = location.artifactPath();
		validatePath(artifactPath);

		// Resolve and normalize to prevent path traversal
		Path resolved = this.basePath.resolve(location.repositoryId()).resolve(artifactPath).normalize();

		// Ensure the resolved path is within the base path
		if (!resolved.startsWith(this.basePath)) {
			throw new IllegalArgumentException("Invalid path: " + artifactPath);
		}

		return resolved;
	}

	private void validatePath(String artifactPath) {
		if (!StringUtils.hasText(artifactPath)) {
			throw new IllegalArgumentException("Artifact path cannot be null or empty");
		}

		// Check for path traversal attempts
		if (artifactPath.contains("..") || artifactPath.contains("~")) {
			throw new IllegalArgumentException("Invalid path: " + artifactPath);
		}
	}

}