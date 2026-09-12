package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;

/**
 * Local file system implementation of StorageService
 */
public class LocalStorageService implements StorageService {

	private final Path basePath;

	public LocalStorageService(KagamiProperties properties) {
		this.basePath = Path.of(Objects.requireNonNull(properties.storage().path(), "kagami.storage.path is required"))
			.toAbsolutePath()
			.normalize();
		try {
			Files.createDirectories(this.basePath);
		}
		catch (IOException e) {
			throw new IllegalStateException("Failed to create storage directory: " + this.basePath, e);
		}
	}

	@Override
	public void store(ArtifactLocation location, InputStream inputStream) throws IOException {
		Path targetPath = resolvePath(location.requireArtifactPath());
		Files.createDirectories(targetPath.getParent());
		Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
	}

	@Override
	public Optional<Resource> retrieve(ArtifactLocation location) {
		Path targetPath = resolvePath(location.requireArtifactPath());
		if (Files.isRegularFile(targetPath)) {
			return Optional.of(new PathResource(targetPath));
		}
		return Optional.empty();
	}

	@Override
	public boolean delete(ArtifactLocation location) throws IOException {
		Path targetPath = resolvePath(location.requireArtifactPath());
		if (!Files.exists(targetPath)) {
			return false;
		}
		if (Files.isDirectory(targetPath)) {
			deleteRecursively(targetPath);
		}
		else {
			Files.delete(targetPath);
		}
		return true;
	}

	@Override
	public List<StorageEntry> list(ArtifactLocation location) throws IOException {
		Path targetPath = resolvePath(location);
		if (!Files.isDirectory(targetPath)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.list(targetPath)) {
			return stream.sorted(Comparator.comparing(path -> path.getFileName().toString()))
				.map(path -> toEntry(location.resolve(path.getFileName().toString()), path))
				.toList();
		}
		catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	@Override
	public Optional<StorageEntry> stat(ArtifactLocation location) throws IOException {
		Path targetPath = resolvePath(location);
		if (!Files.exists(targetPath)) {
			return Optional.empty();
		}
		return Optional.of(toEntry(location, targetPath));
	}

	@Override
	public StorageStats stats(String repositoryId) throws IOException {
		Path repositoryPath = resolvePath(ArtifactLocation.root(repositoryId));
		if (!Files.isDirectory(repositoryPath)) {
			return StorageStats.EMPTY;
		}
		StorageStats.Builder builder = StorageStats.builder();
		try (Stream<Path> stream = Files.walk(repositoryPath)) {
			stream.filter(Files::isRegularFile).forEach(file -> {
				BasicFileAttributes attributes = readAttributes(file);
				builder.addFile(file.getFileName().toString(), attributes.size(),
						attributes.lastModifiedTime().toInstant());
			});
		}
		catch (UncheckedIOException e) {
			throw e.getCause();
		}
		return builder.build();
	}

	private static StorageEntry toEntry(ArtifactLocation location, Path path) {
		BasicFileAttributes attributes = readAttributes(path);
		boolean directory = attributes.isDirectory();
		return StorageEntry.builder()
			.name(location.name())
			.type(directory ? StorageEntryType.DIRECTORY : StorageEntryType.FILE)
			.path(location.artifactPath())
			.size(directory ? null : attributes.size())
			.lastModified(attributes.lastModifiedTime().toInstant())
			.build();
	}

	private static BasicFileAttributes readAttributes(Path path) {
		try {
			return Files.readAttributes(path, BasicFileAttributes.class);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void deleteRecursively(Path directory) throws IOException {
		try (Stream<Path> walk = Files.walk(directory)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.delete(path);
				}
				catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		}
		catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	private Path resolvePath(ArtifactLocation location) {
		Path repositoryPath = this.basePath.resolve(location.repositoryId());
		Path resolved = location.isRoot() ? repositoryPath : repositoryPath.resolve(location.artifactPath());
		resolved = resolved.normalize();
		// ArtifactLocation already rejects traversal; this is a defensive last check
		if (!resolved.startsWith(repositoryPath)) {
			throw new IllegalArgumentException("Invalid path: " + location.artifactPath());
		}
		return resolved;
	}

}
