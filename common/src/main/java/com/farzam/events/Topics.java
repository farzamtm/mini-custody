package com.farzam.events;

/**
 * The topics, by name, in the one place both services read them from.
 *
 * <p>A shared constant rather than a string in each service's configuration. A topic name is half of
 * a contract — the other half is the envelope — and a typo in it does not fail: the producer happily
 * creates {@code custody.withdrawal.v1}, publishes to it forever, and the consumer sitting on the
 * plural name simply never receives anything. Nothing in the logs of either service looks wrong.
 *
 * <p><b>The {@code .v1}.</b> Events are a contract between two deployables that are never upgraded
 * at the same instant, so a change that cannot be made additively needs somewhere to go. Publishing
 * {@code .v2} alongside {@code .v1} lets the new consumer be deployed, verified and cut over while
 * the old one still works, instead of requiring a synchronised release nobody can roll back.
 */
public final class Topics {

    /** custody-api → signer. Keyed by withdrawal id. */
    public static final String WITHDRAWALS = "custody.withdrawals.v1";

    /** signer → custody-api. Keyed by withdrawal id. */
    public static final String SIGNER_RESULTS = "signer.results.v1";

    private Topics() {}

    /**
     * Where a message goes when it has failed every retry.
     *
     * <p>The suffix is defined here rather than inherited from the framework's default, and that is
     * worth a sentence because the alternative looked obviously simpler and was wrong. Spring
     * Kafka's {@code DeadLetterPublishingRecoverer} appended {@code .DLT} for years and changed to
     * {@code -dlt} in version 4. Code that let it decide would have kept working in the worst sense:
     * the recoverer logs a successful publication either way, so the failed messages would have gone
     * on quietly accumulating in a topic nobody had declared and nobody was watching. custody-api's
     * error handler resolves the destination through this method instead.
     *
     * @param topic the topic the message arrived on
     * @return that topic's dead-letter topic
     */
    public static String dlt(String topic) {
        return topic + ".DLT";
    }
}
