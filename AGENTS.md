# AGENTS.md

Instructions for AI coding agents working in this repository. Humans should read
[README.md](README.md) first — it explains what the system does and why. This file
covers only how to work in it.

## The three rules that are not negotiable

**1. Never commit to `main`. Always work on a feature branch.**

Every change — a milestone, a CI tweak, a one-line fix — starts with a new branch
off an up-to-date `main` and lands through a pull request. If you find yourself on
`main` with uncommitted work, branch first and carry the work across.

```bash
git switch main && git pull
git switch -c m5-signer
```

Branch names follow what is already in the history: `m<n>-<slug>` for a milestone
(`m1-ledger`, `m2-withdrawal-api`, `m4-outbox-kafka`), `ci/<slug>` for build and
pipeline work (`ci/quality-gates`), `fix/<slug>` for a bug fix.

**2. Run the pipeline locally before pushing.**

`./gradlew check` runs locally exactly what CI runs on a pull request, so a red
build is something you find before you push, not after. Push only once it is green
from a clean state:

```bash
./gradlew clean check
```

That covers compile under `-Werror`, Spotless, Checkstyle, SpotBugs +
find-sec-bugs, the tests, and the JaCoCo floor. The two gates it does not cover
need Docker images, so run them too when the change touches dependencies or could
plausibly have introduced a secret:

```bash
./gradlew cyclonedxBom          # then scan build/reports/cyclonedx/bom.json with Trivy
docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 \
  detect --source=/repo --redact --config=/repo/.gitleaks.toml
```

After pushing, watch the run to completion rather than assuming it passed:

```bash
gh pr checks --watch
```

A pull request is not ready for review until the single `Gate` check is green. If
CI is red, fix it on the branch — do not ask for a review "while I look at the
failure".

**3. Every pull request opens with a human-readable section.**

The first thing in the PR body is prose a person can read: one or two sentences
saying what this change does and what it closes, before any table, checklist or
detail. No agent preamble, no "this PR implements the changes described in the
issue", no wall of bullet points at the top.

The house style — see PRs #2 through #6 for worked examples — is:

```markdown
Closes M4. Approving a withdrawal writes an `outbox` row in the same transaction
as the state change, a relay moves it to Kafka, and results from the signer come
back in through an idempotent consumer.

## What this adds
| | |
| --- | --- |
| `outbox/` | `OutboxWriter`, `OutboxRelay` (`SKIP LOCKED`), the timer as a separate bean |

## The three M4 criteria
- [x] Approving produces exactly one `WithdrawalApproved` — `<test name>`

## Decisions worth reviewing
**The send happens while the rows are locked.** … why, and what the alternative costs.

## Verification
`./gradlew clean check` green from scratch: 128 tests, line coverage 96.3% …
```

Sections after the opening paragraph, in this order and only where they have
something to say:

| Section | Contains |
| --- | --- |
| **What this adds** | A table of package or file → one line on what it owns |
| **The N criteria** | The milestone's acceptance criteria, each ticked and each naming the test that proves it |
| **Decisions worth reviewing** | Two or three judgement calls, bolded claim then the reasoning and what the alternative costs |
| **Bugs the tests caught** | Anything that went wrong on the way and why it was not obvious |
| **Verification** | What was run, the numbers, and any manual end-to-end walk-through |
| **Incidental changes** | Anything in the diff that is not the headline, called out so a reviewer is not surprised |

Write it for a reviewer who has not read the code yet. State the trade-off you
made and what it costs, rather than only what you built — a decision with no cost
named reads as one that was not made.

End PR descriptions with the attribution line the tooling asks for.

## The workflow, start to finish

1. `git switch main && git pull`, then branch.
2. Make the change. Write the test first when the behaviour is testable — this
   codebase's rule is that a property worth claiming has a test rather than a
   comment.
3. `./gradlew spotlessApply` to fix formatting rather than argue with it.
4. `./gradlew clean check` until green.
5. Commit. Subject line matches the history: `M5: <what it does>` for a milestone,
   `CI: <what it does>` for build work. Imperative, no trailing full stop.
6. Push and open the PR with the structure above.
7. `gh pr checks --watch` until `Gate` is green.

Do not push, open a PR, or merge unless the user asked for it.

## What the build enforces

`./gradlew check` fails on any of these, so it is cheaper to know them up front
than to discover them in a red run.

| Gate | Rejects |
| --- | --- |
| `javac -Xlint:all -Werror` | Any compiler warning |
| Spotless (Eclipse JDT) | Anything `./gradlew spotlessApply` would change |
| Checkstyle | Unused imports, swallowed exceptions, `System.out`, methods over 12 branches, and **`float`/`double` anywhere** |
| SpotBugs + find-sec-bugs | Null derefs, resource leaks, SQL injection, weak crypto, predictable RNG |
| JaCoCo | Line coverage below 94%, per module (`-PcoverageMinimum=0` to bypass temporarily, never in a commit) |
| Gitleaks | Credentials anywhere in history, including Ethereum private keys |
| Trivy over the CycloneDX SBOM | A new fixable HIGH or CRITICAL CVE |

The coverage floor ratchets up per milestone. Raise it in `build.gradle.kts` when
a milestone lands above it; never lower it.

## Conventions that are not obvious from the code

**Money is `BigInteger` wei, never a floating-point type.** Checkstyle rejects
`float` and `double` outright. Amounts cross the API as decimal integer strings,
because a JSON number is a double in most parsers and one ETH is 10^18 wei.

**The OpenAPI contract is written before the code.**
`custody-api/src/main/resources/openapi.yaml` generates the interfaces the
controllers implement. There is no `@GetMapping` in this repository and there
should not be one — change the contract, then make the controller compile again.

**Generated code lives in its own source set and is held to no gate.** Do not
relax a flag module-wide to accommodate generator output.

**The ledger is append-only.** Nothing outside `LedgerService.post` touches
`accounts.balance`. A mistake is corrected with a reversing entry, never an
`UPDATE`.

**Every consumer is idempotent.** Delivery is at-least-once, so a consumer inserts
the event id into `processed_events` in the same transaction as the state change.

**Tests use Testcontainers against real Postgres, not H2.** The ledger depends on
`FOR UPDATE SKIP LOCKED`, `jsonb`, partial indexes and `numeric(78,0)`; an H2 test
would pass while production broke. Test method names are full sentences
(`theSameResultDeliveredTwiceChangesStateOnce`), and integration tests extend
`AbstractPostgresTest` or `AbstractKafkaTest`. A Postgres-only test carries
`@WithoutKafka`.

**A decision gets an ADR.** `docs/adr/`, numbered, stating what was rejected and
why — not only what was chosen. Link it from the README section it belongs to.

## Layout

| Module | Owns |
| --- | --- |
| `common` | The Kafka event contract, and the Ed25519 verification both services share. Deliberately has no Spring dependency — do not add one. |
| `custody-api` | Clients, the double-entry ledger, withdrawals, approvals, the REST API. |
| `signer` | Wallet keys. The only component that can sign. Has no web starter, and must not gain one. |

Milestone status lives in the README's Milestones table. M0 to M6 have all landed;
anything from here is new work, and whatever defines it gets a row in that table
and is ticked by the pull request that finishes it.

## Running it locally

Requires JDK 25 and Docker.

```bash
docker compose up -d      # Postgres, Kafka (KRaft), Anvil
./gradlew :custody-api:bootRun
```

`bootRun` uses the `dev` profile, which is what maps `POST /dev/deposits` and
`POST /dev/withdrawals/{id}/approve`. The README has a full deposit → whitelist →
withdraw → approve walk-through worth running before claiming a change works end
to end.
