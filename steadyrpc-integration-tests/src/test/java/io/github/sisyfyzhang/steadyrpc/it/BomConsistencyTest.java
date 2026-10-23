package io.github.sisyfyzhang.steadyrpc.it;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * steadyrpc-bom 不继承根 pom，版本号与它管理的第三方版本只能在 steadyrpc-bom/pom.xml 里写死一份（docs/releasing.md）。
 * 这里核对两处没有走样：
 * <ul>
 *   <li>steadyrpc-bom 的 {@code <version>} 与根 pom 的 {@code <version>} 相同；</li>
 *   <li>steadyrpc-bom 的 {@code zookeeper.version} 与根 pom 的 {@code zookeeper.version} 相同（steadyrpc-bom 唯一管理的第三方依赖）；</li>
 *   <li>steadyrpc-bom 管理的本框架模块集合 = 根 pom 的 {@code <modules>} 里的发布模块
 *       （去掉 steadyrpc-bom 自己与 release profile 里 central-publishing 的 {@code excludeArtifacts}）。</li>
 * </ul>
 */
class BomConsistencyTest {

	private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
	private static final String OWN_GROUP = "${project.groupId}";

	@Test
	void bomVersionAndZookeeperVersionMatchTheRootPom() throws Exception {
		Element root = parse(ROOT.resolve("pom.xml"));
		Element bom = parse(ROOT.resolve("steadyrpc-bom/pom.xml"));

		assertEquals(childText(root, "version"), childText(bom, "version"), "steadyrpc-bom <version> must equal the root pom <version>");
		assertEquals(property(root, "zookeeper.version"), property(bom, "zookeeper.version"),
				"steadyrpc-bom zookeeper.version must equal the root pom zookeeper.version");
	}

	@Test
	void bomManagesExactlyThePublishedModules() throws Exception {
		Element root = parse(ROOT.resolve("pom.xml"));
		Element bom = parse(ROOT.resolve("steadyrpc-bom/pom.xml"));

		Set<String> published = new TreeSet<>(texts(child(root, "modules"), "module"));
		published.remove("steadyrpc-bom");
		List<String> excluded = excludedFromPublishing(root);
		assertFalse(excluded.isEmpty(), "no excludeArtifacts found in the release profile; did the pom layout change?");
		excluded.forEach(published::remove);

		Set<String> managed = new TreeSet<>();
		Set<String> thirdParty = new TreeSet<>();
		for (Element dependency : elements(child(child(bom, "dependencyManagement"), "dependencies"), "dependency")) {
			if (OWN_GROUP.equals(childText(dependency, "groupId"))) {
				managed.add(childText(dependency, "artifactId"));
			} else {
				thirdParty.add(childText(dependency, "groupId") + ":" + childText(dependency, "artifactId"));
			}
		}
		assertEquals(published, managed, "steadyrpc-bom must manage exactly the published modules of the root pom");
		// 只管 ZooKeeper：Netty / jackson 留给 Spring Boot 的 BOM 与本框架的直接依赖（SECURITY.md「依赖漏洞」）
		assertEquals(Set.of("org.apache.zookeeper:zookeeper"), thirdParty, "steadyrpc-bom manages ZooKeeper as its only third-party dependency");
	}

	private static List<String> excludedFromPublishing(Element root) {
		List<String> excluded = new ArrayList<>();
		NodeList lists = root.getElementsByTagName("excludeArtifacts");
		for (int i = 0; i < lists.getLength(); i++) {
			excluded.addAll(texts((Element) lists.item(i), "artifact"));
		}
		return excluded;
	}

	private static String property(Element project, String name) {
		return childText(child(project, "properties"), name);
	}

	private static Element parse(Path pom) throws ParserConfigurationException, SAXException, IOException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		Document document = factory.newDocumentBuilder().parse(pom.toFile());
		return document.getDocumentElement();
	}

	private static List<Element> elements(Element parent, String name) {
		List<Element> result = new ArrayList<>();
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element element && element.getTagName().equals(name)) {
				result.add(element);
			}
		}
		return result;
	}

	private static Element child(Element parent, String name) {
		List<Element> found = elements(parent, name);
		assertEquals(1, found.size(), "expected exactly one <" + name + "> under <" + parent.getTagName() + ">");
		return found.get(0);
	}

	private static String childText(Element parent, String name) {
		return child(parent, name).getTextContent().trim();
	}

	private static List<String> texts(Element parent, String name) {
		return elements(parent, name).stream().map(e -> e.getTextContent().trim()).toList();
	}
}
