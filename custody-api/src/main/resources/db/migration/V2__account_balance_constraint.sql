-- V1 is applied and therefore immutable: Flyway records its checksum, and editing
-- the file makes the application refuse to start. Changing a constraint means a
-- new migration, which is also how it would have to work in production.

-- V1 protected only CLIENT accounts from going negative. That leaves PENDING_OUT
-- and BANK_OPERATING unguarded, and both of them are debited by code that has not
-- been written yet (settlement in M6, network fees in M5) — exactly the situation
-- where a database constraint earns its keep. The application enforces the same
-- rule in AccountType.mayGoNegative(); this is the copy that survives a bug in it.
--
-- EXTERNAL is excluded because a negative EXTERNAL balance is not damage, it is the
-- accounting: it represents money that came in from the outside world, and its
-- negation is what the hot wallet should hold on chain.
alter table accounts
  drop constraint client_non_negative;

alter table accounts
  add constraint accounts_non_negative
  check (type = 'EXTERNAL' or balance >= 0);
