package com.feynman.rpc.core.registry;

import java.util.List;

/** {@link Registry#subscribe(String, RegistryWatcher)} 的回调接口。 */
@FunctionalInterface
public interface RegistryWatcher {

	void onChange(List<String> children);

}
