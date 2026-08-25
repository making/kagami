package am.ik.kagami.mockserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal forward HTTP proxy behaving like the proxies found in legacy environments: it
 * only understands the absolute URI request form of HTTP/1.1, it optionally challenges
 * the client with {@code Proxy-Authenticate: Basic} and it closes the connection after
 * every exchange.
 */
public class MockProxyServer implements AutoCloseable {

	private static final List<String> HOP_BY_HOP_HEADERS = List.of("connection", "keep-alive", "proxy-authorization",
			"proxy-connection", "proxy-authenticate");

	private final ServerSocket serverSocket;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final List<String> forwardedRequests = new CopyOnWriteArrayList<>();

	private final AtomicInteger authenticationChallenges = new AtomicInteger();

	private final String expectedAuthorization;

	private volatile boolean running;

	private MockProxyServer(String expectedAuthorization) {
		this.expectedAuthorization = expectedAuthorization;
		try {
			this.serverSocket = new ServerSocket(0);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Create a proxy that forwards every request without authentication.
	 * @return a new proxy server
	 */
	public static MockProxyServer withoutAuthentication() {
		return new MockProxyServer(null);
	}

	/**
	 * Create a proxy that requires Basic proxy authentication.
	 * @param username the user name the client has to send
	 * @param password the password the client has to send
	 * @return a new proxy server
	 */
	public static MockProxyServer withBasicAuthentication(String username, String password) {
		String credentials = Base64.getEncoder()
			.encodeToString("%s:%s".formatted(username, password).getBytes(StandardCharsets.UTF_8));
		return new MockProxyServer("Basic " + credentials);
	}

	public int port() {
		return this.serverSocket.getLocalPort();
	}

	/**
	 * The absolute URIs of the requests that have been forwarded to the target servers,
	 * in the form {@code GET http://host:port/path}.
	 * @return the forwarded requests
	 */
	public List<String> forwardedRequests() {
		return List.copyOf(this.forwardedRequests);
	}

	/**
	 * The number of requests that have been challenged with {@code 407 Proxy
	 * Authentication Required}.
	 * @return the number of authentication challenges
	 */
	public int authenticationChallenges() {
		return this.authenticationChallenges.get();
	}

	public void run() {
		this.running = true;
		this.executor.execute(this::acceptLoop);
	}

	@Override
	public void close() {
		this.running = false;
		try {
			this.serverSocket.close();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		this.executor.shutdownNow();
	}

	private void acceptLoop() {
		while (this.running) {
			try {
				Socket socket = this.serverSocket.accept();
				this.executor.execute(() -> handle(socket));
			}
			catch (IOException e) {
				if (this.running) {
					throw new UncheckedIOException(e);
				}
				return;
			}
		}
	}

	private void handle(Socket socket) {
		try (socket) {
			InputStream input = socket.getInputStream();
			OutputStream output = socket.getOutputStream();
			String requestLine = readLine(input);
			if (requestLine == null || requestLine.isEmpty()) {
				return;
			}
			String[] tokens = requestLine.split(" ");
			if (tokens.length < 3) {
				sendError(output, 400, "Bad Request");
				return;
			}
			String method = tokens[0];
			String target = tokens[1];
			List<String> headers = readHeaders(input);
			if (!isAuthorized(headers)) {
				this.authenticationChallenges.incrementAndGet();
				sendProxyAuthenticationRequired(output);
				return;
			}
			URI uri = resolveTarget(target, headers);
			if (uri == null || uri.getHost() == null) {
				sendError(output, 400, "Bad Request");
				return;
			}
			this.forwardedRequests.add("%s %s".formatted(method, uri));
			forward(method, uri, headers, input, output);
		}
		catch (IOException e) {
			// The client is gone, nothing can be reported anymore
		}
	}

	private void forward(String method, URI uri, List<String> headers, InputStream clientInput,
			OutputStream clientOutput) throws IOException {
		int port = uri.getPort() != -1 ? uri.getPort() : 80;
		try (Socket target = new Socket()) {
			target.connect(new InetSocketAddress(uri.getHost(), port));
			OutputStream targetOutput = target.getOutputStream();
			StringBuilder request = new StringBuilder();
			String pathAndQuery = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
			if (uri.getRawQuery() != null) {
				pathAndQuery += "?" + uri.getRawQuery();
			}
			request.append("%s %s HTTP/1.1\r\n".formatted(method, pathAndQuery));
			headers.stream()
				.filter(header -> !isHopByHop(header))
				.forEach(header -> request.append(header).append("\r\n"));
			request.append("Connection: close\r\n\r\n");
			targetOutput.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
			copyBody(headers, clientInput, targetOutput);
			targetOutput.flush();
			relayResponse(target.getInputStream(), clientOutput);
		}
	}

	/**
	 * Relay the response of the target server, replacing its connection management
	 * headers because the connection to the client is always closed afterwards.
	 */
	private void relayResponse(InputStream targetInput, OutputStream clientOutput) throws IOException {
		String statusLine = readLine(targetInput);
		if (statusLine == null) {
			sendError(clientOutput, 502, "Bad Gateway");
			return;
		}
		StringBuilder response = new StringBuilder(statusLine).append("\r\n");
		readHeaders(targetInput).stream()
			.filter(header -> !isHopByHop(header))
			.forEach(header -> response.append(header).append("\r\n"));
		response.append("Connection: close\r\n\r\n");
		clientOutput.write(response.toString().getBytes(StandardCharsets.ISO_8859_1));
		targetInput.transferTo(clientOutput);
		clientOutput.flush();
	}

	private void copyBody(List<String> headers, InputStream input, OutputStream output) throws IOException {
		long contentLength = headers.stream()
			.filter(header -> header.toLowerCase(Locale.ROOT).startsWith("content-length:"))
			.mapToLong(header -> Long.parseLong(header.substring(header.indexOf(':') + 1).trim()))
			.findFirst()
			.orElse(0);
		for (long i = 0; i < contentLength; i++) {
			int read = input.read();
			if (read == -1) {
				return;
			}
			output.write(read);
		}
	}

	private boolean isAuthorized(List<String> headers) {
		if (this.expectedAuthorization == null) {
			return true;
		}
		return headers.stream()
			.filter(header -> header.toLowerCase(Locale.ROOT).startsWith("proxy-authorization:"))
			.map(header -> header.substring(header.indexOf(':') + 1).trim())
			.anyMatch(this.expectedAuthorization::equals);
	}

	private static URI resolveTarget(String target, List<String> headers) {
		if (!target.startsWith("/")) {
			return URI.create(target);
		}
		// Fall back to the origin form, which requires the Host header to locate the
		// target server
		return headers.stream()
			.filter(header -> header.toLowerCase(Locale.ROOT).startsWith("host:"))
			.map(header -> URI.create("http://" + header.substring(header.indexOf(':') + 1).trim() + target))
			.findFirst()
			.orElse(null);
	}

	private static boolean isHopByHop(String header) {
		String name = header.substring(0, Math.max(header.indexOf(':'), 0)).toLowerCase(Locale.ROOT);
		return HOP_BY_HOP_HEADERS.contains(name);
	}

	private static void sendProxyAuthenticationRequired(OutputStream output) throws IOException {
		output.write("""
				HTTP/1.1 407 Proxy Authentication Required\r
				Proxy-Authenticate: Basic realm="kagami-legacy-proxy"\r
				Content-Length: 0\r
				Connection: close\r
				\r
				""".getBytes(StandardCharsets.ISO_8859_1));
		output.flush();
	}

	private static void sendError(OutputStream output, int status, String reason) throws IOException {
		output.write("""
				HTTP/1.1 %d %s\r
				Content-Length: 0\r
				Connection: close\r
				\r
				""".formatted(status, reason).getBytes(StandardCharsets.ISO_8859_1));
		output.flush();
	}

	private static List<String> readHeaders(InputStream input) throws IOException {
		List<String> headers = new ArrayList<>();
		String line;
		while ((line = readLine(input)) != null && !line.isEmpty()) {
			headers.add(line);
		}
		return headers;
	}

	private static String readLine(InputStream input) throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		int read;
		while ((read = input.read()) != -1) {
			if (read == '\n') {
				break;
			}
			if (read != '\r') {
				line.write(read);
			}
		}
		if (read == -1 && line.size() == 0) {
			return null;
		}
		return line.toString(StandardCharsets.ISO_8859_1);
	}

}
