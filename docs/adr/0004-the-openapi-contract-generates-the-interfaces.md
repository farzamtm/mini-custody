# 4. The OpenAPI contract generates the interfaces the controllers implement

- **Status:** accepted
- **Date:** 2026-09-23
- **Milestone:** M2

## Context

There are two ways to end up with an OpenAPI document. Annotate the controllers and
let springdoc extract one, or write the document first and generate code from it.

The extracted version is easier and is almost always what happens. Its problem is
ordering: a contract that is a by-product of the implementation cannot be reviewed
before the implementation exists, cannot be given to a consumer to build against in
parallel, and cannot disagree with the code — which sounds like a feature until you
notice that it also cannot catch the code being wrong. It documents whatever was
built, including the accident.

Writing it first inverts that. The contract is the artefact under review; the code
is what has to satisfy it. But that only holds if something enforces it. A
hand-written controller next to a hand-written YAML file drifts within a week, and
then the YAML is worse than no documentation because people believe it.

## Decision

**`custody-api/src/main/resources/openapi.yaml` is written by hand, before the
code**, and `openapi-generator` turns it into one Java interface per tag plus the
DTOs. `WithdrawalController implements WithdrawalsApi`. There is no `@GetMapping` in
any controller in this repository: the verbs, the paths, the status types and the
Bean Validation constraints all come from the contract. Changing the contract
changes the interface, and the controller stops compiling. That compile error is the
entire point.

**`interfaceOnly=true`.** The generator can emit controller classes too, and then
every contract change regenerates them and throws the bodies away.

**`skipDefaultInterface=true`**, so an operation in the contract with no
implementation is a compile error rather than a runtime `501`.

**Generated models are suffixed `Dto`.** `Withdrawal`, `Account` and
`WithdrawalStatus` are all names the domain already uses, and the two meanings are
genuinely different — the entity is what the database holds, the DTO is what the
contract promises. The suffix lets both be imported into one file and makes every
mapper an explicit crossing of that boundary. `WithdrawalDtos` and `AccountDtos` are
four-line hand-written mappers rather than MapStruct: the interesting part is that
`BigInteger` becomes a decimal *string*, because a JSON number is a double in most
parsers and 10^18 wei does not survive one, and a mapper that made that implicit
would be hiding the only thing worth reading.

**Generated code compiles in its own Gradle source set.** The root build uses
`-Xlint:all -Werror`, and openapi-generator 7.14 does not survive it: it emits
`org.springframework.lang.Nullable`, deprecated in Spring Framework 7 in favour of
JSpecify, and puts its "do not edit" banner above the `package` line, which javac
reads as a dangling doc comment. Neither is fixable from this repository. Relaxing
the flags for the whole module would be the easy fix and the wrong one — the
warnings are worth most exactly where a human is typing. So `generated` is a source
set of its own, compiled with `-nowarn`, and `main` depends on its output.
Checkstyle, SpotBugs and JaCoCo are all per-source-set, so they skip it for free
rather than through a list of path exclusions that would need maintaining.

## Consequences

- `-parameters` has to be re-added to the generated compile task by hand, because
  clearing the compiler arguments to drop `-Werror` also drops the flag Spring Boot
  normally supplies. Without it, a Bean Validation failure on the `Idempotency-Key`
  header reports itself against `arg0` instead of naming the header.
- Two sources of validation errors. The generated interfaces carry `@Validated`,
  which puts a validating proxy in front of the controller, and that proxy throws
  Bean Validation's `ConstraintViolationException` rather than Spring MVC's
  `HandlerMethodValidationException`. `ApiErrors` handles both and returns the same
  shape, because which one fires depends on how the controller happens to be proxied
  and that is not something a client should be able to observe.
- The build now depends on a code generator, which is a real cost: a generator
  release can change the output, and the failure shows up as a compile error in code
  nobody wrote. The mitigation is that the version is pinned and the generated tree
  is not in version control, so a regeneration diff is never confused with a change.
- Errors are Spring's `ProblemDetail` rather than the generated `ProblemDto`. The
  contract documents the shape and `ProblemDetail` already serialises to exactly it,
  including extension members like `code`; going through the DTO would add a mapping
  layer that could only ever make the two disagree.
- `POST /dev/deposits` is in the published contract even though it only exists under
  the `dev` profile. Documenting it is honest — it is a real endpoint of a real
  deployment — and the description says plainly where it is and is not mapped.
