package com.farzam.signer.crypto;

import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The key that wraps every other key.
 *
 * <p>Top of the envelope: it never encrypts a private key directly, only the per-wallet data keys
 * that do. {@link EnvelopeCipher} explains why that indirection is worth a table column.
 *
 * <p><b>It comes from the environment, and that is the part that is a stand-in.</b> In a real
 * deployment this is a KMS or an HSM, and the distinction is not where the bytes are stored but
 * whether they ever exist in this process at all: a KMS is handed the wrapped data key and returns
 * the unwrapped one, so the master key never leaves the module. Here it is loaded into the heap,
 * which means a heap dump of this JVM is game over. That is the single biggest gap between this
 * project and a custody system somebody would be allowed to run, and it is why the README's
 * production-differences section leads with it.
 *
 * <p><b>Nothing here can be wiped, and pretending otherwise would be worse than admitting it.</b>
 * The value arrives as a {@code String} because that is what a property source holds, and Spring's
 * {@code Environment} keeps its own reference for the lifetime of the context. Zeroing the decoded
 * array below removes one copy out of several; {@link SecretKeySpec} keeps another that has no
 * public way to clear it. The honest summary is that an environment variable is not a way to keep a
 * key secret from anything with access to the process — it is a way to keep it out of git.
 */
/*
 * Final, and SpotBugs is right to have asked for it. A constructor that throws leaves a
 * partially-initialised object, and in a subclassable class that object is reachable: a subclass
 * with a finalizer runs against it after the exception, holding whatever was assigned before the
 * failure. That is a textbook attack and this is a textbook target — the one field is a key.
 * Nothing needs to extend this.
 */
@Component
public final class MasterKey {

    /** AES-256. A shorter key would be rejected rather than silently used. */
    private static final int KEY_BYTES = 32;

    private final SecretKey key;

    /**
     * @param base64 the master key, 32 bytes base64-encoded, from {@code SIGNER_MASTER_KEY}
     * @throws IllegalStateException if it is missing or not 32 bytes
     */
    MasterKey(@Value("${signer.master-key:}") String base64) {
        if (base64.isBlank()) {
            // Fail at startup, not at the first withdrawal. A signer that boots without its master
            // key is a signer that will discover the problem while somebody is waiting for money,
            // and whose health check was green the whole time.
            throw new IllegalStateException(
                    "signer.master-key is not set; the signer cannot decrypt any wallet key without it");
        }

        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException malformed) {
            // Deliberately not including the value, or its length, or the parser's message: this is
            // the one string in the process that must never reach a log, and a startup failure is
            // read by whoever set it, who does not need to be told what they typed.
            throw new IllegalStateException("signer.master-key is not valid base64");
        }

        if (raw.length != KEY_BYTES) {
            int length = raw.length;
            Arrays.fill(raw, (byte) 0);
            throw new IllegalStateException("signer.master-key must decode to " + KEY_BYTES + " bytes, got " + length);
        }

        // SecretKeySpec copies the array, so this clears the one copy that is ours to clear.
        this.key = new SecretKeySpec(raw, "AES");
        Arrays.fill(raw, (byte) 0);
    }

    /**
     * @return the key-encryption key, for wrapping and unwrapping data keys only
     */
    SecretKey key() {
        return key;
    }
}
