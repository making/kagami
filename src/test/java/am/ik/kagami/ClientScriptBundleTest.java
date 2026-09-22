package am.ik.kagami;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the wiring between the client scripts on disk, the bundle definition in
 * {@code pom.xml} and the {@code <script>} tags in the layout.
 *
 * <p>
 * A script that is not listed in the Closure Compiler {@code <includes>} is silently
 * missing from {@code app.min.js}, so the feature simply stops working once deployed. The
 * same goes in reverse for a script on disk that nobody loads.
 */
class ClientScriptBundleTest {

	private static final Path SCRIPT_DIR = Path.of("src/main/resources/static/js");

	/**
	 * Scripts that are deliberately kept out of {@code app.min.js}, and why. A new entry
	 * here should be a considered decision, not a way to silence this test.
	 */
	private static final Map<String, String> NOT_BUNDLED = Map.of("vendor/htmx.min.js",
			"htmx 4 uses private class fields, which Closure Compiler cannot parse; loaded as its own <script>");

	@Test
	void everyClientScriptIsEitherBundledOrDocumentedAsAnException() throws Exception {
		// Fails when a new file under static/js is neither added to the bundle nor listed
		// above.
		assertThat(String.join("\n", scriptsOnDisk())).isEqualToNormalizingWhitespace(String.join("\n",
				Stream.concat(bundledScripts().stream(), NOT_BUNDLED.keySet().stream()).sorted().toList()));
	}

	@Test
	void layoutLoadsTheBundleAndVendoredHtmx() throws Exception {
		String layout = Files.readString(Path.of("src/main/resources/templates/layouts/head.mustache"),
				StandardCharsets.UTF_8);
		assertThat(layout).contains("{{#src}}/js/app.min.js{{/src}}");
		assertThat(layout).contains("{{#src}}/js/vendor/htmx.min.js{{/src}}");
	}

	private static List<String> scriptsOnDisk() throws IOException {
		try (Stream<Path> files = Files.walk(SCRIPT_DIR)) {
			return files.filter(path -> path.getFileName().toString().endsWith(".js"))
				.map(path -> SCRIPT_DIR.relativize(path).toString())
				.sorted()
				.toList();
		}
	}

	/** The Closure Compiler {@code <includes>} list, in concatenation order. */
	private static List<String> bundledScripts()
			throws IOException, ParserConfigurationException, SAXException, XPathExpressionException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		Document pom;
		try (var pomStream = Files.newInputStream(Path.of("pom.xml"))) {
			pom = factory.newDocumentBuilder().parse(pomStream);
		}
		// local-name() keeps the expression readable despite the POM namespace.
		NodeList includes = (NodeList) XPathFactory.newInstance()
			.newXPath()
			.evaluate("//*[local-name()='plugin'][*[local-name()='artifactId']='closure-compiler-maven-plugin']"
					+ "//*[local-name()='include']", pom, XPathConstants.NODESET);
		return IntStream.range(0, includes.getLength())
			.mapToObj(i -> includes.item(i).getTextContent().trim())
			.toList();
	}

}
