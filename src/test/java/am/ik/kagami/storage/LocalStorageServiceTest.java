package am.ik.kagami.storage;

import am.ik.kagami.KagamiProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for LocalStorageService
 */
class LocalStorageServiceTest {

	@TempDir
	Path storagePath;

	private static final ArtifactLocation LOCATION = new ArtifactLocation("mock",
			"com/example/demo/1.0.0/demo-1.0.0.jar");

	private static final byte[] STORED = "the artifact that is already stored".getBytes(StandardCharsets.UTF_8);

	private static final byte[] REPLACEMENT = "the artifact that replaces it".getBytes(StandardCharsets.UTF_8);

	private LocalStorageService storageService() {
		KagamiProperties properties = KagamiProperties.builder()
			.storage(new KagamiProperties.Storage(this.storagePath.toString()))
			.repositories(Map.of())
			.proxy(KagamiProperties.Proxy.builder().url("").build())
			.jwt(new KagamiProperties.Jwt(null, null))
			.authentication(new KagamiProperties.Authentication(KagamiProperties.AuthenticationType.SIMPLE, List.of()))
			.build();
		return new LocalStorageService(properties);
	}

	@Test
	void storeAndRetrieveArtifact() throws Exception {
		LocalStorageService storageService = storageService();

		storageService.store(LOCATION, new ByteArrayInputStream(STORED));

		Resource resource = storageService.retrieve(LOCATION).orElseThrow();
		assertThat(resource.contentLength()).isEqualTo(STORED.length);
		assertThat(resource.getContentAsByteArray()).isEqualTo(STORED);
	}

	@Test
	void storeLeavesNoTemporaryFileBehind() throws Exception {
		LocalStorageService storageService = storageService();

		storageService.store(LOCATION, new ByteArrayInputStream(STORED));

		Path artifactDirectory = this.storagePath.resolve("mock/com/example/demo/1.0.0");
		try (Stream<Path> files = Files.list(artifactDirectory)) {
			assertThat(files.map(path -> path.getFileName().toString())).containsExactly("demo-1.0.0.jar");
		}
	}

	@Test
	void artifactBeingStoredIsNotVisibleUntilItIsComplete() throws Exception {
		LocalStorageService storageService = storageService();
		storageService.store(LOCATION, new ByteArrayInputStream(STORED));

		CountDownLatch partiallyWritten = new CountDownLatch(1);
		CountDownLatch resumeWriting = new CountDownLatch(1);
		Thread writer = new Thread(() -> {
			try {
				storageService.store(LOCATION, blockingInputStream(REPLACEMENT, partiallyWritten, resumeWriting));
			}
			catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		writer.start();
		try {
			assertThat(partiallyWritten.await(10, TimeUnit.SECONDS)).isTrue();

			// While the replacement is only partially written, a concurrent request must
			// still see the previously stored artifact in full
			Resource resource = storageService.retrieve(LOCATION).orElseThrow();
			assertThat(resource.contentLength()).isEqualTo(STORED.length);
			assertThat(resource.getContentAsByteArray()).isEqualTo(STORED);
		}
		finally {
			resumeWriting.countDown();
			writer.join(TimeUnit.SECONDS.toMillis(10));
		}

		Resource resource = storageService.retrieve(LOCATION).orElseThrow();
		assertThat(resource.contentLength()).isEqualTo(REPLACEMENT.length);
		assertThat(resource.getContentAsByteArray()).isEqualTo(REPLACEMENT);
	}

	@Test
	void deleteArtifact() throws Exception {
		LocalStorageService storageService = storageService();
		storageService.store(LOCATION, new ByteArrayInputStream(STORED));

		assertThat(storageService.delete(LOCATION)).isTrue();
		assertThat(storageService.retrieve(LOCATION)).isEmpty();
		assertThat(storageService.delete(LOCATION)).isFalse();
	}

	/**
	 * Create an input stream that hands out a first small chunk and then blocks, so that
	 * a store is observably in the middle of writing its content.
	 * @param content the content to stream
	 * @param partiallyWritten counted down once the first chunk has been written
	 * @param resumeWriting awaited before the remaining content is streamed
	 * @return the blocking input stream
	 */
	private static InputStream blockingInputStream(byte[] content, CountDownLatch partiallyWritten,
			CountDownLatch resumeWriting) {
		return new InputStream() {

			private final InputStream delegate = new ByteArrayInputStream(content);

			private final AtomicInteger reads = new AtomicInteger();

			@Override
			public int read() throws IOException {
				return this.delegate.read();
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				int read = this.reads.incrementAndGet();
				if (read == 1) {
					// Hand out only a few bytes so that the destination stays incomplete
					return this.delegate.read(b, off, Math.min(len, 4));
				}
				if (read == 2) {
					partiallyWritten.countDown();
					try {
						resumeWriting.await();
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				return this.delegate.read(b, off, len);
			}
		};
	}

}
