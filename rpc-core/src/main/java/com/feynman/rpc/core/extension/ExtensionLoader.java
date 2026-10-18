package com.feynman.rpc.core.extension;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 精简版 Dubbo 风格 SPI 加载器。被 {@link SPI} 标注的接口，其扩展实现按每行
 * {@code name=impl.ClassName} 的格式声明在 classpath 下的
 * {@code META-INF/rpc/<接口全限定名>} 文件里。
 *
 * <p>SPI 的职责是"按名字找到实现类"。两种取实例的方式对应两类扩展：
 * <ul>
 *   <li>{@link #getExtension}：缓存单例，只用于无状态扩展（Serializer、RegistryFactory）；</li>
 *   <li>{@link #newExtension}：每次新建，用于有状态扩展（LoadBalance 的轮询游标、Cluster 的重试线程），
 *       由 RpcClient / RpcServer 持有并随之关闭，同 JVM 的多个实例互不干扰。</li>
 * </ul>
 * 类级的 {@code LOADERS} 与类定义缓存是 rpc-core 允许的少数静态状态之一：它只缓存"类路径上有什么"，
 * 与任何运行时实例无关。
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

	/** 缓存的单例实例，仅适用于无状态扩展。 */
	public T getExtension(String name) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("Extension name must not be empty");
		}
		return instanceCache.computeIfAbsent(name, this::createExtension);
	}

	/** 每次调用都新建一个实例，调用方负责其生命周期。 */
	public T newExtension(String name) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("Extension name must not be empty");
		}
		return createExtension(name);
	}

	/** 该类型在 classpath 上声明过的所有扩展名（不只是已经实例化的那些）。 */
	public Set<String> getSupportedExtensions() {
		return Collections.unmodifiableSet(getExtensionClasses().keySet());
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
