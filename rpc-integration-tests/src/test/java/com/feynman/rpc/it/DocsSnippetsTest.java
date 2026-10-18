package com.feynman.rpc.it;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开文档里的代码必须是真代码：README.md 与 docs/getting-started.md 中每个 {@code ```java} / {@code ```yaml} 代码块，
 * 都必须逐字（忽略行尾空白与换行符差异）出现在 rpc-examples 的某个源文件或配置文件里。示例代码本身由
 * {@link ExamplesEndToEndTest}、{@link PlainJavaExamplesTest} 跑通，两者合起来保证"照抄文档就能工作"。
 * 改了示例却忘了同步文档（或反过来）时，这里失败并指出是哪一块。
 */
class DocsSnippetsTest {

	private static final Pattern BLOCK = Pattern.compile("```(java|yaml)\\n(.*?)```", Pattern.DOTALL);
	private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

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
		System.out.printf("[docs] %s: %d java/yaml block(s) checked against rpc-examples%n", document, checked);
		assertTrue(checked > 0, document + " has no java/yaml blocks; did the pattern break?");
		assertTrue(missing.isEmpty(), document + ": blocks not found verbatim in rpc-examples sources: " + missing);
	}

	private static List<String> exampleSources() throws IOException {
		try (Stream<Path> files = Files.walk(ROOT.resolve("rpc-examples/src/main"))) {
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
