package io.github.sisyfyzhang.steadyrpc.it;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开文档里的代码必须是真代码：README.md 与 docs/getting-started.md 中每个 {@code ```java} / {@code ```yaml} 代码块，
 * 都必须逐字（忽略行尾空白与换行符差异）出现在 steadyrpc-examples 的某个源文件或配置文件里。示例代码本身由
 * {@link ExamplesEndToEndTest}、{@link PlainJavaExamplesTest} 跑通，两者合起来保证"照抄文档就能工作"。
 * 改了示例却忘了同步文档（或反过来）时，这里失败并指出是哪一块。
 *
 * <p>{@code ```xml} 代码块（pom 片段）只核对坐标：其中每个 {@code <artifactId>} 都必须出现在 steadyrpc-examples/pom.xml
 * 或 steadyrpc-bom/pom.xml 里（{@link #NOT_IN_EXAMPLES} 列出的刻意例外除外），模块改名或文档拼错时失败。bash 块不核对。
 *
 * <p>2.0.0 改名（{@code com.feynman.rpc} → {@code io.github.sisyfyzhang.steadyrpc}）的残留检查见
 * {@link #noPreRenameResidueInPublicDocs()}：示例日志、路径里的旧包名不在代码块核对范围内，靠它兜底。
 */
class DocsSnippetsTest {

	private static final Pattern BLOCK = Pattern.compile("```(java|yaml)\\n(.*?)```", Pattern.DOTALL);
	private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
	private static final Pattern XML_BLOCK = Pattern.compile("```xml\\n(.*?)```", Pattern.DOTALL);
	private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
	/** 文档里出现、但 steadyrpc-examples 刻意不依赖的 artifact → 原因。 */
	private static final Map<String, String> NOT_IN_EXAMPLES = Map.of(
			"spring-boot-starter-webmvc", "getting-started 7.1: scraping /actuator/prometheus needs a web server; the examples run as WebApplicationType.NONE");

	/** 改名前的包名残留：Boot 缩写的 logger 名（c.f.r.* / c.f.rpc.*）与路径形式，任何公开文档里都不应出现。 */
	private static final List<String> FORBIDDEN_EVERYWHERE = List.of("c.f.r", "c.f.rpc", "com/feynman");
	/** {@code com.feynman} 只允许出现在这些文件的迁移说明里（1.0.0 → 2.0.0）。 */
	private static final Set<String> OLD_PACKAGE_ALLOWED_IN = Set.of(
			"CHANGELOG.md", "README.md", "README.en.md", "docs/getting-started.md", "docs/releasing.md");

	@Test
	void noPreRenameResidueInPublicDocs() throws IOException {
		List<Path> documents = new ArrayList<>();
		for (String top : List.of("README.md", "README.en.md", "CHANGELOG.md", "CONTRIBUTING.md", "SECURITY.md")) {
			documents.add(ROOT.resolve(top));
		}
		try (Stream<Path> files = Files.walk(ROOT.resolve("docs"))) {
			files.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".md")).sorted().forEach(documents::add);
		}
		List<String> violations = new ArrayList<>();
		for (Path document : documents) {
			String relative = ROOT.relativize(document).toString().replace('\\', '/');
			List<String> lines = Files.readAllLines(document, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				for (String forbidden : FORBIDDEN_EVERYWHERE) {
					if (line.contains(forbidden)) {
						violations.add(relative + ":" + (i + 1) + " contains '" + forbidden + "'");
					}
				}
				if (line.contains("com.feynman") && !OLD_PACKAGE_ALLOWED_IN.contains(relative)) {
					violations.add(relative + ":" + (i + 1) + " contains 'com.feynman' (allowed only in " + new TreeSet<>(OLD_PACKAGE_ALLOWED_IN) + ")");
				}
			}
		}
		System.out.printf("[docs] pre-rename residue: %d document(s) scanned%n", documents.size());
		assertTrue(documents.size() > 10, "expected README / CHANGELOG / CONTRIBUTING / SECURITY and docs/**/*.md, got " + documents);
		assertTrue(violations.isEmpty(), "pre-rename residue in public docs: " + violations);
	}

	@ParameterizedTest
	@ValueSource(strings = { "README.md", "docs/getting-started.md" })
	void everyJavaAndYamlBlockIsCopiedFromRpcExamples(String document) throws IOException {
		List<String> sources = exampleSources();
		String markdown = normalize(Files.readString(ROOT.resolve(document), StandardCharsets.UTF_8));
		Matcher matcher = BLOCK.matcher(markdown);
		int checked = 0;
		List<String> missing = new ArrayList<>();
		while (matcher.find()) {
			String block = trimBlankLines(matcher.group(2));
			checked++;
			if (sources.stream().noneMatch(source -> source.contains(block))) {
				missing.add(block.lines().findFirst().orElse("") + " …");
			}
		}
		System.out.printf("[docs] %s: %d java/yaml block(s) checked against steadyrpc-examples%n", document, checked);
		assertTrue(checked > 0, document + " has no java/yaml blocks; did the pattern break?");
		assertTrue(missing.isEmpty(), document + ": blocks not found verbatim in steadyrpc-examples sources: " + missing);
	}

	@ParameterizedTest
	@ValueSource(strings = { "README.md", "docs/getting-started.md" })
	void everyArtifactIdInXmlBlocksIsAModuleOfTheBomOrADependencyOfRpcExamples(String document) throws IOException {
		String known = Files.readString(ROOT.resolve("steadyrpc-examples/pom.xml"), StandardCharsets.UTF_8)
				+ Files.readString(ROOT.resolve("steadyrpc-bom/pom.xml"), StandardCharsets.UTF_8);
		String markdown = normalize(Files.readString(ROOT.resolve(document), StandardCharsets.UTF_8));
		Matcher blocks = XML_BLOCK.matcher(markdown);
		TreeSet<String> checked = new TreeSet<>();
		TreeSet<String> missing = new TreeSet<>();
		while (blocks.find()) {
			Matcher ids = ARTIFACT_ID.matcher(blocks.group(1));
			while (ids.find()) {
				String artifactId = ids.group(1).trim();
				checked.add(artifactId);
				if (!known.contains("<artifactId>" + artifactId + "</artifactId>") && !NOT_IN_EXAMPLES.containsKey(artifactId)) {
					missing.add(artifactId);
				}
			}
		}
		System.out.printf("[docs] %s: artifactIds in xml blocks %s%n", document, checked);
		assertTrue(!checked.isEmpty(), document + " has no <artifactId> in xml blocks; did the pattern break?");
		assertTrue(missing.isEmpty(), document + ": artifactIds neither in steadyrpc-examples/pom.xml nor in steadyrpc-bom/pom.xml: " + missing);
	}

	private static List<String> exampleSources() throws IOException {
		try (Stream<Path> files = Files.walk(ROOT.resolve("steadyrpc-examples/src/main"))) {
			List<String> sources = new ArrayList<>();
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				sources.add(normalize(Files.readString(file, StandardCharsets.UTF_8)));
			}
			return sources;
		}
	}

	private static String normalize(String text) {
		StringBuilder sb = new StringBuilder();
		text.replace("\r\n", "\n").lines().forEach(line -> sb.append(line.stripTrailing()).append('\n'));
		return sb.toString();
	}

	private static String trimBlankLines(String block) {
		String[] lines = block.split("\n", -1);
		int start = 0;
		int end = lines.length;
		while (start < end && lines[start].isBlank()) {
			start++;
		}
		while (end > start && lines[end - 1].isBlank()) {
			end--;
		}
		return String.join("\n", java.util.Arrays.copyOfRange(lines, start, end)) + "\n";
	}
}
