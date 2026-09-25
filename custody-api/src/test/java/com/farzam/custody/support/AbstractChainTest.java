package com.farzam.custody.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * One Postgres and one Anvil, shared by every test that needs to ask the chain something.
 *
 * <p>Extends {@link AbstractPostgresTest} and adds a node, in the same shape and for the same
 * reasons: a static container started once and left to Ryuk, rather than one per test class, because
 * a node per class would dominate the runtime of the suite.
 *
 * <p><b>The watcher's timer is off</b>, though not by anything here — {@code test/resources/
 * application.properties} turns it off for the whole module. Every test here calls
 * {@code checkBatch()} itself, so it can assert on what one pass did; with the timer running, a test
 * that mines two blocks and expects the withdrawal still to be waiting is racing a background
 * thread, and that flake appears on a loaded CI runner and not on a laptop.
 *
 * <p>This class deliberately does <em>not</em> set that property, even though it is the one that
 * cares most. {@code @DynamicPropertySource} outranks {@code @TestPropertySource}, so a value set
 * here could not be overridden by a subclass — and {@code ConfirmationSchedulingTest}, whose whole
 * subject is the timer, is a subclass that has to turn it back on.
 */
public abstract class AbstractChainTest extends AbstractPostgresTest {

    @SuppressWarnings("resource") // stopped by Ryuk at JVM exit, by design
    private static final AnvilContainer ANVIL = new AnvilContainer();

    static {
        ANVIL.start();
    }

    /**
     * Points the application at the container's randomly-assigned RPC port.
     *
     * @param registry Spring's test property registry
     */
    @DynamicPropertySource
    static void chainProperties(DynamicPropertyRegistry registry) {
        registry.add("chain.rpc-url", ANVIL::rpcUrl);
    }

    /**
     * @return a client for arranging what the chain says, sharing no code with the one under test
     */
    protected static TestChain chain() {
        return new TestChain(ANVIL.rpcUrl());
    }
}
