package com.feynman.rpc.core.extension;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 精简版 Dubbo 风格 SPI 加载器。被 {@link SPI} 标注的接口，其扩展实现按每行
 * {@code name=impl.ClassName} 的格式声明在 classpath 下的
 * {@code META-INF/rpc/<接口全限定名>} 文件里。
 */
public final class ExtensionLoader<T> {

	private static final String CONFIG_DIR = "META-INF/rpc/";

	private static final ConcurrentHashMap<Class<?>, ExtensionLoader<?>> LOADERS = new ConcurrentHashMap<>();

	private final Class<T> type;
	private final ConcurrentHashMap<String, T> instanceCache = new ConcurrentHashMap<>();
	private volatile Map<String, Class<?>> extensionClasses;

	private ExtensionLoader(Class<T> type) {
		this.type = type;
	}

	@SuppressWarnings("unchecked")
	public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
		if (type == null) {
			throw new IllegalArgumentException("Extension type must not be null");
		}
		if (!type.isInterface()) {
			throw new IllegalArgumentException("Extension type (" + type.getName() + ") is not an interface");
		}
		if (!type.isAnnotationPresent(SPI.class)) {
			throw new IllegalArgumentException("Extension type (" + type.getName() + ") is not annotated with @SPI");
		}
		return (ExtensionLoader<T>) LOADERS.computeIfAbsent(type, ExtensionLoader::new);
	}

	public T getExtension(String name) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("Extension name must not be empty");
		}
		return instanceCache.computeIfAbsent(name, this::createExtension);
	}

	/** 该类型在 classpath 上声明过的所有扩展名（不只是已经实例化的那些）。 */
	public java.util.Set<String> getSupportedExtensions() {
		return java.util.Collections.unmodifiableSet(getExtensionClasses().keySet());
	}

	public T getDefaultExtension() {
		String defaultName = type.getAnnotation(SPI.class).value();
		if (defaultName == null || defaultName.isEmpty()) {
			throw new IllegalStateException("Extension type (" + type.getName() + ") declares no default @SPI name");
		}
		return getExtension(defaultName);
	}

	private T createExtension(String name) {
		Class<?> clazz = getExtensionClasses().get(name);
		if (clazz == null) {
			throw new IllegalStateException("No extension named '" + name + "' found for type " + type.getName());
		}
		try {
			return type.cast(clazz.getDeclaredConstructor().newInstance());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Failed to instantiate extension '" + name + "' (" + clazz.getName() + ")", e);
		}
	}

	private Map<String, Class<?>> getExtensionClasses() {
		Map<String, Class<?>> classes = extensionClasses;
		if (classes == null) {
			synchronized (this) {
				classes = extensionClasses;
				if (classes == null) {
					classes = loadExtensionClasses();
					extensionClasses = classes;
				}
			}
		}
		return classes;
	}

	private Map<String, Class<?>> loadExtensionClasses() {
		Map<String, Class<?>> classes = new ConcurrentHashMap<>();
		String fileName = CONFIG_DIR + type.getName();
		ClassLoader classLoader = ExtensionLoader.class.getClassLoader();
		try {
			Enumeration<URL> resources = classLoader.getResources(fileName);
			while (resources.hasMoreElements()) {
				loadResource(classes, classLoader, resources.nextElement());
			}
		} catch (Exception e) {
			throw new IllegalStateException("Failed to load extension config file " + fileName, e);
		}
		return classes;
	}

	private void loadResource(Map<String, Class<?>> classes, ClassLoader classLoader, URL url) {
		try (InputStream in = url.openStream();
				BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				int commentIdx = line.indexOf('#');
				if (commentIdx >= 0) {
					line = line.substring(0, commentIdx);
				}
				line = line.trim();
				if (line.isEmpty()) {
					continue;
				}
				int eq = line.indexOf('=');
				if (eq <= 0 || eq == line.length() - 1) {
					throw new IllegalStateException("Malformed extension line '" + line + "' in " + url);
				}
				String name = line.substring(0, eq).trim();
				String className = line.substring(eq + 1).trim();
				classes.put(name, Class.forName(className, false, classLoader));
			}
		} catch (Exception e) {
			throw new IllegalStateException("Failed to parse extension config file " + url, e);
		}
	}
}
