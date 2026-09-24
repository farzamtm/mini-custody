# 0007 — Wallet keys are envelope-encrypted, and lent rather than handed out

Status: accepted (M5)

## Context

The signer holds the private key for a hot wallet. That key is the funds: anyone
who reads it can move everything the wallet holds, with no further compromise
needed anywhere else in the system. Two questions follow, and they have different
answers.

**At rest.** The key has to survive a restart, so it has to be written down
somewhere, and the obvious somewhere is the signer's own Postgres. Storing it in
the clear means a SQL injection, a leaked backup or a read-only replica handed to
an analyst is a total loss. So it is encrypted — but with what, and how does that
key get here?

**In memory.** Even encrypted at rest, the key exists in plaintext for the
duration of a signature. Java makes that harder than it sounds: a `String` is
immutable and cannot be wiped, `BigInteger` likewise, and the garbage collector
may have copied either somewhere before anyone thought about it.

The constraint on both is that this project stands in a KMS-shaped hole with an
environment variable, and the design has to be the one you would keep when the
variable is replaced by a real KMS.

## Decision

**Envelope encryption, with the wallet address as additional authenticated data.**

```
master key (env var, standing in for a KMS)
  wraps    -> data key, one per wallet, 32 random bytes
    encrypts -> the private key            (AES-256-GCM, AAD = the address)
```

Three properties, each of which is why a layer exists.

*The master key encrypts key material and nothing else.* Rotating it means
unwrapping and re-wrapping a handful of 32-byte data keys, with no private key
touched. Rotating a master key that encrypted everything directly would mean
every secret in the system in plaintext in one process for the length of the
migration. It is also what makes the KMS substitution a local change: a KMS is
handed the wrapped data key and returns the unwrapped one, so only `EnvelopeCipher`
would move.

*GCM, so a wrong answer is impossible.* Decryption either returns exactly what
was sealed or throws. That matters more here than in most places: a silently
corrupted key would not fail anywhere downstream — it would produce a perfectly
valid signature from an address nobody controls and nobody is reconciling, and
the funds would be gone with nothing in any log.

*The address is AAD, so a ciphertext only decrypts in its own row.* Anyone with
write access to the signer's database can run `update wallet_keys set
encrypted_private_key = (...)` and point a row they control at a key they do not.
Binding the address into the authentication tag makes that copy fail to decrypt.
Without AAD the attack is one statement.

**In memory, keys are lent for the length of a lambda.** `WalletKeys` exposes
`withPrivateKey(address, use)` and no `loadPrivateKey(address)`. The bytes are
decrypted, passed to the callback and zeroed in a `finally`. A method that
returned the array would make every caller responsible for wiping it, and one of
them eventually would not.

## Consequences

**The guarantee is narrower than it looks, and saying so is part of the
decision.** web3j's `ECKeyPair` holds the private key as a `BigInteger`, which is
immutable and cannot be wiped, and the signing path has to construct one. The
array this code allocates is cleared; the copy the crypto library made from it
survives until collection. Closing that gap needs a signing library that works in
`byte[]` throughout, or an HSM that never returns the key at all. The second is
the real answer and it is what a regulated deployment would use.

**The master key is in the heap, so a heap dump of this process is game over.**
An environment variable is not a way to keep a key secret from something with
access to the process; it is a way to keep it out of git. It also cannot be
wiped: the value arrives as a `String` from a property source and Spring's
`Environment` keeps its own reference for the life of the context. `MasterKey`
says this in full rather than implying a protection it does not provide.

**One address, one row, no derivation.** There is no HD wallet and no key per
client. That is a real gap — a deposit service wants a fresh address per client,
which is what BIP-32 extended public keys are for — and it is out of scope for a
project with one hot wallet and no deposits.

## Alternatives rejected

**Encrypt the private key directly with the master key.** Simpler by one column
and one function. It gives up cheap rotation and, more importantly, puts the
master key on the data path, which is the thing that has to change when a KMS
arrives.

**Keep keys in a file or a mounted secret rather than in Postgres.** Avoids the
database as an exfiltration route, and replaces it with the filesystem, plus a
second thing to back up consistently with the signing log that references it. The
signer already owns a database that nothing else may read; a second store with
different operational properties is not obviously safer and is certainly more to
get wrong.

**No AAD, on the grounds that database write access is already fatal.** It very
nearly is, and the difference is worth a line of code: with AAD, an attacker with
write access still has to compromise the signer's process or its master key
before a key they control can be used.
