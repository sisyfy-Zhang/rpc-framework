package com.feynman.rpc.core.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InFlightGateTest {

	@Test
	void closedGateRejectsNewWorkButCountsExistingWork() throws Exception {
		InFlightGate gate = new InFlightGate();
		assertTrue(gate.tryEnter());
		assertTrue(gate.tryEnter());
		gate.close();
		assertFalse(gate.tryEnter());
		assertEquals(2, gate.inFlight());
		assertFalse(gate.awaitIdle(50, TimeUnit.MILLISECONDS), "two still in flight");

		CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> {
			try {
				return gate.awaitIdle(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
		});
		gate.exit();
		gate.exit();
		assertTrue(waiter.get(5, TimeUnit.SECONDS));
		assertEquals(0, gate.inFlight());
	}

	@Test
	void unmatchedExitIsAProgrammingError() {
		assertThrows(IllegalStateException.class, () -> new InFlightGate().exit());
	}
}
