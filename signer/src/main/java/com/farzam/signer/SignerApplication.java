package com.farzam.signer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the signer.
 *
 * <p>This service is deliberately small and deliberately mute. It exposes no HTTP endpoint for
 * signing: the only way to make it sign is to publish a {@code WithdrawalApproved} event, and even
 * then it re-verifies the approval signatures against its <em>own</em> list of trusted public keys
 * before it acts. An attacker who fully owns custody-api still cannot move funds.
 *
 * <p>{@code @EnableScheduling} drives two timers: the outbox relay, which moves results to Kafka,
 * and the broadcast retry job, which resends any transaction in {@code signing_log} that is not
 * known to have reached the network.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SignerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SignerApplication.class, args);
    }
}
