# Running it end to end

One withdrawal against a local instance, from an empty database to a confirmed
transaction on a local chain. The [README](../README.md) is the short version — what the
system is and how to start it; [design.md](design.md) is the argument for why it is
built this way.

It is not a single paste. The approval succeeds, and then the signer refuses it until two
key pairs exist and both services restart with them, which is the design rather than a
rough edge.

## What you need

Docker and a JDK. The build compiles against 25; if that is not the JDK on your `PATH`,
Gradle finds an installed one or downloads it, so no `JAVA_HOME` juggling.

One case the toolchain cannot rescue: if `JAVA_HOME` is *set* but points at a directory
that is gone — what Homebrew leaves behind when it upgrades a JDK out from under you —
the `gradlew` script refuses to start and Gradle never runs at all. Unset it, or point it
at a JDK that exists.

## Starting it

```bash
docker compose up -d      # Postgres, Kafka (KRaft), Anvil
./gradlew build           # compiles and runs the tests
./gradlew :custody-api:bootRun
```

`bootRun` starts with the `dev` profile, which is what maps `POST /dev/deposits` — a
local instance with no way to put money into it is not much use. A real deployment sets
its own profile and the bean is never created, so the path does not exist.

It listens on **8090**, not Spring's default 8080, which on most machines is already
taken by something else. To move it again, pass the port through the environment —
`SERVER_PORT=9000 ./gradlew :custody-api:bootRun` — and not through `--args`. The `dev`
profile is itself set as a task argument, and `--args=` on the command line *replaces*
those rather than adding to them, so `--args='--server.port=9000'` turns the profile off
as well and `/dev/deposits` starts returning 404.

## Deposit, whitelist, withdraw

```bash
CLIENT=$(uuidgen | tr 'A-Z' 'a-z')
DEST=0x70997970c51812dc3a010c7d01b50e0d17dc79c8

ACCOUNT=$(curl -s localhost:8090/dev/deposits -H 'Content-Type: application/json' \
  -d "{\"clientId\":\"$CLIENT\",\"amountWei\":\"1000000000000000000\"}" | jq -r .id)

curl -s localhost:8090/v1/clients/$CLIENT/whitelist -H 'Content-Type: application/json' \
  -d "{\"address\":\"$DEST\"}"

WITHDRAWAL=$(curl -s localhost:8090/v1/withdrawals -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"accountId\":\"$ACCOUNT\",\"destination\":\"$DEST\",\"amountWei\":\"400000000000000000\"}" | jq -r .id)

curl -s localhost:8090/v1/accounts/$ACCOUNT   # 600000000000000000 — 0.4 is held
```

Send that request twice with the same `Idempotency-Key` and the balance still reads 0.6:
the retry returns the original withdrawal and holds nothing extra.

## Approve

Approving needs an approver with a real key pair:

```bash
openssl genpkey -algorithm ed25519 -out /tmp/alice.pem

# The raw 32 bytes the registry and the signer both hold: an Ed25519
# SubjectPublicKeyInfo is a fixed 12-byte DER prefix and then the key.
PUBKEY=$(openssl pkey -in /tmp/alice.pem -pubout -outform DER | tail -c 32 | base64)

APPROVER=$(curl -s localhost:8090/dev/approvers -H 'Content-Type: application/json' \
  -d "{\"name\":\"alice\",\"publicKey\":\"$PUBKEY\"}" | jq -r .id)

# Exactly the bytes the server rebuilds and verifies against: three keys, sorted, no
# whitespace, no trailing newline. Get any of it wrong and you signed a different
# document — which is the whole idea.
printf '{"amountWei":"400000000000000000","destination":"%s","withdrawalId":"%s"}' \
  "$DEST" "$WITHDRAWAL" > /tmp/statement.json

SIGNATURE=$(openssl pkeyutl -sign -rawin -inkey /tmp/alice.pem -in /tmp/statement.json | base64)

curl -s localhost:8090/v1/withdrawals/$WITHDRAWAL/approvals -H 'Content-Type: application/json' \
  -d "{\"approverId\":\"$APPROVER\",\"signature\":\"$SIGNATURE\"}"
# {"collected":1,"required":1,"status":"APPROVED", …} — 0.4 ETH is below the
# four-eyes threshold, so one approver is the quorum
```

Change a digit of the amount in `statement.json` and the same call returns `422` with
`"code":"INVALID_APPROVAL_SIGNATURE"`: the server signs off on what it holds, not on
what was sent.

## The keys the signer will not start without

The signer now consumes the event and still refuses it, until Alice's key is in *its*
configuration. It also needs a key pair of its own, so `custody-api` can tell a real
result from a forged one — plus a master key to unseal wallet keys with, and the one hot
wallet it is allowed to sign from. None of those four has a default: each missing one is
a startup failure naming exactly what is absent, because a signer that boots without its
keys is one that finds out while somebody is waiting for money.

```bash
openssl genpkey -algorithm ed25519 -out /tmp/signer-results.pem
RESULTS_SEED=$(openssl pkey -in /tmp/signer-results.pem -outform DER | tail -c 32 | base64)
RESULTS_PUBKEY=$(openssl pkey -in /tmp/signer-results.pem -pubout -outform DER | tail -c 32 | base64)

# Stand-in for the KMS: AES-256, so 32 bytes. Keep it — the wallet key is sealed
# under it, and a fresh one cannot open what the old one wrapped.
MASTER_KEY=$(openssl rand -base64 32)

# Anvil's first account. The private key is only read on the first boot of a fresh
# database; after that it is sealed in `wallet_keys` and the variable is a copy of a
# secret with no reader, so drop it.
HOT_ADDR=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
HOT_PK=0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80

SIGNER_MASTER_KEY=$MASTER_KEY \
SIGNER_HOT_WALLET_ADDRESS=$HOT_ADDR \
SIGNER_HOT_WALLET_PRIVATE_KEY=$HOT_PK \
SIGNER_POLICY_TRUSTED_APPROVERS_0_ID=$APPROVER \
SIGNER_POLICY_TRUSTED_APPROVERS_0_PUBLIC_KEY=$PUBKEY \
SIGNER_RESULTS_SIGNING_KEY=$RESULTS_SEED \
  ./gradlew :signer:bootRun

# custody-api needs the matching public key, so restart it with:
CUSTODY_SIGNER_RESULTS_PUBLIC_KEY=$RESULTS_PUBKEY ./gradlew :custody-api:bootRun
```

## Watching it settle

Compose runs Anvil with `--block-time 2`, so once something is signed the withdrawal
walks itself the rest of the way:

```bash
# BROADCAST, then "confirmations": 1, 2, 3, then CONFIRMED — about six seconds
watch -n1 "curl -s localhost:8090/v1/withdrawals/$WITHDRAWAL | jq '{status, confirmations, txHash}'"

curl -s localhost:8090/v1/accounts/$ACCOUNT   # still 0.6: the hold became an outflow,
                                              # it did not come back
curl -s localhost:8090/v1/reconciliation | jq
```

## Two things worth trying next

[Publishing a forged refusal](design.md#events) to watch `custody-api` reject it, and
[`POST /dev/withdrawals/{id}/approve`](design.md#the-signer), which approves with nobody
approving so the signer's refusal can be seen by hand. How the tests are built —
Testcontainers, a real Postgres and a real Anvil rather than stubs — is under
[Tests](design.md#tests).
