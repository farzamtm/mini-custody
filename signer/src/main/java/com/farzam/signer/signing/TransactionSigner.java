package com.farzam.signer.signing;

import com.farzam.signer.chain.ChainProperties;
import com.farzam.signer.chain.Fees;
import com.farzam.signer.crypto.HotWallet;
import com.farzam.signer.crypto.WalletKeys;
import java.math.BigInteger;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

/**
 * Turns an approved withdrawal into signed bytes.
 *
 * <p>The only place in this system where a private key is in memory, and it is deliberately the
 * shortest class in the signing path: build the transaction, borrow the key for one call, return the
 * bytes. Nothing here decides anything. Whether to sign at all is {@link SigningPolicy}'s question,
 * which nonce to use is {@link Nonces}', and both have been answered before this is called.
 *
 * <p><b>EIP-1559, type 2.</b> The chain id inside the transaction is what makes the signature
 * chain-specific: EIP-155 folded it into the signed payload precisely so that a transaction signed
 * on a testnet cannot be replayed on mainnet, where the same address holds real money.
 *
 * <p><b>The hash is computed here, not obtained from the network.</b> A transaction hash is
 * keccak-256 of the signed bytes, so it is knowable the instant the signature exists and before
 * anybody has been told about it. That is what lets the {@code WithdrawalBroadcast} event be written
 * in the same database transaction as the signature — the identifier custody-api will use to follow
 * this payment is already final.
 */
@Component
public class TransactionSigner {

    private final WalletKeys walletKeys;
    private final ChainProperties chain;
    private final String from;

    TransactionSigner(WalletKeys walletKeys, ChainProperties chain, HotWallet hotWallet) {
        this.walletKeys = walletKeys;
        this.chain = chain;
        this.from = hotWallet.requireAddress();
    }

    /**
     * Signs one transfer.
     *
     * @param withdrawalId which withdrawal this is for
     * @param destination where the funds go
     * @param amountWei how much, in wei
     * @param nonce the nonce already reserved for it
     * @param fees what to offer for gas
     * @return the signed transaction, its hash already known
     * @throws com.farzam.signer.crypto.UnknownWalletException if the hot wallet has no stored key
     * @throws com.farzam.signer.crypto.KeyUnsealingException if the stored key does not decrypt
     */
    public SignedTransaction sign(UUID withdrawalId, String destination, BigInteger amountWei, long nonce, Fees fees) {
        RawTransaction transaction = RawTransaction.createEtherTransaction(
                chain.chainId(),
                BigInteger.valueOf(nonce),
                chain.gasLimit(),
                destination.toLowerCase(Locale.ROOT),
                amountWei,
                fees.maxPriorityFeePerGas(),
                fees.maxFeePerGas());

        // withPrivateKey wipes the array when this lambda returns. What it cannot wipe is the copy
        // ECKeyPair makes: web3j holds the key as a BigInteger, which is immutable, so that copy
        // lives until it is collected. WalletKeys says so in full rather than claiming a guarantee
        // this code cannot give.
        byte[] signed = walletKeys.withPrivateKey(
                from,
                privateKey -> TransactionEncoder
                        .signMessage(transaction, Credentials.create(ECKeyPair.create(privateKey))));

        return new SignedTransaction(
                withdrawalId,
                Numeric.toHexString(Hash.sha3(signed)),
                Numeric.toHexString(signed),
                nonce);
    }
}
