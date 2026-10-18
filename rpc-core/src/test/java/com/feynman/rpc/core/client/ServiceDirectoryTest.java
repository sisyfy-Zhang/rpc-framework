package com.feynman.rpc.core.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.testutil.Instances;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceDirectoryTest {

	@Test
	void emptyPushIsIgnoredWhenProtectionIsOnAndListWasNonEmpty() {
		AtomicInteger changes = new AtomicInteger();
		ServiceDirectory directory = new ServiceDirectory(Instances.KEY, true, d -> changes.incrementAndGet());

		directory.onInstancesChanged(List.of(Instances.at(1), Instances.at(2)));
		directory.onInstancesChanged(List.of());

		assertEquals(2, directory.instances().size(), "empty protection must keep the previous providers");
		assertEquals(1, changes.get(), "an ignored empty push must not trigger connection changes");
	}

	@Test
	void emptyPushIsAppliedWhenProtectionIsOff() {
		ServiceDirectory directory = new ServiceDirectory(Instances.KEY, false, d -> {
		});

		directory.onInstancesChanged(List.of(Instances.at(1)));
		directory.onInstancesChanged(List.of());

		assertTrue(directory.instances().isEmpty());
	}

	@Test
	void initialEmptyPushIsAcceptedEvenWithProtection() {
		// 一开始就没有提供者不是"抖动"，没有旧列表可保留
		AtomicInteger changes = new AtomicInteger();
		ServiceDirectory directory = new ServiceDirectory(Instances.KEY, true, d -> changes.incrementAndGet());

		directory.onInstancesChanged(List.of());

		assertTrue(directory.instances().isEmpty());
		assertEquals(1, changes.get());
	}

	@Test
	void nonEmptyUpdatesReplaceTheList() {
		ServiceDirectory directory = new ServiceDirectory(Instances.KEY, true, d -> {
		});

		directory.onInstancesChanged(List.of(Instances.at(1), Instances.at(2)));
		directory.onInstancesChanged(List.of(Instances.at(3)));

		assertEquals(List.of(Instances.at(3)), directory.instances());
	}
}
