package com.farzam.signer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the signer.
 *
 * <p>This service is deliberately small and deliberately mute. It exposes no HTTP
 * endpoint for signing: the only way to make it sign is to publish a
 * {@code WithdrawalApproved} event, and even then it re-verifies the approval
 * signatures against its <em>own</em> list of trusted public keys before it acts.
 * An attacker who fully owns custody-api still cannot move funds.
 *
 * <p>{@code @EnableScheduling} is for the M5 retry job that re-broadcasts any
 * transaction in {@code signing_log} that has no receipt yet.
 */
@SpringBootApplication
@EnableScheduling
public class SignerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SignerApplication.class, args);
    }
}
