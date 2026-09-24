package com.farzam.signer.chain;

import java.math.BigInteger;

/**
 * What a transaction offers to pay for gas, in EIP-1559's terms.
 *
 * <p>The two numbers are not a price and a discount, which is the usual first misreading. Every
 * block has a <em>base fee</em> set by the protocol from how full the previous block was, and it is
 * burned rather than paid to anyone. {@code maxFeePerGas} is the most the sender will let the base
 * fee plus the tip come to; {@code maxPriorityFeePerGas} is the part that actually goes to the
 * validator, and is the only part they have any reason to care about. The sender is charged
 * {@code baseFee + tip} and refunded the rest, so a generous {@code maxFeePerGas} buys durability
 * against a rising base fee and costs nothing.
 *
 * @param maxPriorityFeePerGas the tip, in wei per gas
 * @param maxFeePerGas the ceiling on base fee plus tip, in wei per gas
 */
public record Fees(BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas) {}
