package am.ik.kagami.mockserver;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;

public class MockServer implements AutoCloseable {

	private final HttpServer server;

	private final List<Rule> rules = new ArrayList<>();

	private final int port;

	private final HttpContext context;

	private Response fallbackResponse = Response.notFound();

	public MockServer(int port) {
		try {
			this.server = HttpServer.create(new InetSocketAddress(port), 0);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		this.port = port;
		this.context = this.server.createContext("/", new MockHandler());
		this.server.setExecutor(Executors.newSingleThreadExecutor());
	}

	public void addFilter(Filter filter) {
		this.context.getFilters().add(filter);
	}

	public int port() {
		return port;
	}

	public MockServer GET(String path, Function<Request, Response> responseFunction) {
		return route(request -> request.method() == HttpMethod.GET && request.path().equals(path), responseFunction);
	}

	public MockServer POST(String path, Function<Request, Response> responseFunction) {
		return route(request -> request.method() == HttpMethod.POST && request.path().equals(path), responseFunction);
	}

	public MockServer PUT(String path, Function<Request, Response> responseFunction) {
		return route(request -> request.method() == HttpMethod.PUT && request.path().equals(path), responseFunction);
	}

	public MockServer DELETE(String path, Function<Request, Response> responseFunction) {
		return route(request -> request.method() == HttpMethod.DELETE && request.path().equals(path), responseFunction);
	}

	public MockServer PATCH(String path, Function<Request, Response> responseFunction) {
		return route(request -> request.method() == HttpMethod.PATCH && request.path().equals(path), responseFunction);
	}

	public MockServer HEAD(String path, Function<Request, Response> responseFunction) {
		return route(request -> request.method() == HttpMethod.HEAD && request.path().equals(path), responseFunction);
	}

	public MockServer OPTIONS(String path, Function<Request, Response> responseFunction) {
		return route(request -> request.method() == HttpMethod.OPTIONS && request.path().equals(path),
				responseFunction);
	}

	public MockServer route(Predicate<Request> requestMatcher, Function<Request, Response> responseFunction) {
		rules.add(new Rule(requestMatcher, responseFunction));
		return this;
	}

	public MockServer fallback(Response fallbackResponse) {
		this.fallbackResponse = fallbackResponse;
		return this;
	}

	public MockServer reset() {
		rules.clear();
		return this;
	}

	public void run() {
		server.start();
	}

	@Override
	public void close() {
		server.stop(0);
	}

	public record Request(HttpMethod method, String path, Map<String, String> queryParams) {
		public static Request from(HttpExchange exchange) {
			HttpMethod method = HttpMethod.valueOf(exchange.getRequestMethod());
			URI uri = exchange.getRequestURI();
			String path = uri.getPath();
			Map<String, String> queryParams = parseQueryString(uri.getQuery());
			return new Request(method, path, queryParams);
		}

		private static Map<String, String> parseQueryString(String query) {
			Map<String, String> params = new HashMap<>();
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

		public String queryParam(String name) {
			return queryParams.get(name);
		}
	}

	public record Response(int status, String body, Map<String, String> headers) {

		public static Builder builder() {
			return new Builder();
		}

		public static Response ok(String body) {
			return new Response(200, body, Map.of());
		}

		public static Response ok(String body, String contentType) {
			return new Response(200, body, Map.of("Content-Type", contentType));
		}

		public static Response notFound() {
			return new Response(404, "Not Found", Map.of("Content-Type", "text/plain"));
		}

		public static Response json(String body) {
			return new Response(200, body, Map.of("Content-Type", "application/json"));
		}

		public static class Builder {

			private int status = 200;

			private String body = "";

			private Map<String, String> headers = new HashMap<>();

			public Builder status(int status) {
				this.status = status;
				return this;
			}

			public Builder body(String body) {
				this.body = body;
				return this;
			}

			public Builder header(String name, String value) {
				this.headers.put(name, value);
				return this;
			}

			public Builder contentType(String contentType) {
				return header("Content-Type", contentType);
			}

			public Builder headers(Map<String, String> headers) {
				this.headers.putAll(headers);
				return this;
			}

			public Response build() {
				return new Response(status, body, Map.copyOf(headers));
			}

		}
	}

	private record Rule(Predicate<Request> matcher, Function<Request, Response> responseFunction) {
		public boolean matches(Request request) {
			return matcher.test(request);
		}

		public Response respond(Request request) {
			return responseFunction.apply(request);
		}
	}

	private class MockHandler implements HttpHandler {

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			var request = Request.from(exchange);
			var response = findMatchingResponse(request);
			sendResponse(exchange, response);
		}

		private Response findMatchingResponse(Request request) {
			return rules.stream()
				.filter(rule -> rule.matches(request))
				.findFirst()
				.map(rule -> rule.respond(request))
				.orElse(fallbackResponse);
		}

		private void sendResponse(HttpExchange exchange, Response response) throws IOException {
			response.headers().forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
			var responseBody = response.body().getBytes();
			exchange.sendResponseHeaders(response.status(), responseBody.length);
			try (var os = exchange.getResponseBody()) {
				os.write(responseBody);
			}
		}

	}

	public enum HttpMethod {

		GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS

	}

}