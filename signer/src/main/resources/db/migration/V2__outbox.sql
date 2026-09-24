-- The signer publishes too, so it needs the same dual-write protection
-- custody-api has had since M0.
--
-- Without it the sequence is: sign, commit, publish. A crash between the
-- commit and the publish leaves a withdrawal that has been signed — the
-- nonce spent, the raw transaction sitting in signing_log — and a custody-api
-- that will never hear about it. The withdrawal stays APPROVED for ever and
-- the client's funds stay held, with nothing in either service's logs looking
-- like an error. Publishing first is worse, as ever: custody-api would record
-- a transaction hash for a signature that was rolled back.
--
-- So the WithdrawalBroadcast row is written by the same transaction that
-- writes signing_log, and the relay moves it to Kafka afterwards. Identical in
-- shape to custody-api's table, deliberately: ADR 0009 says why the two are
-- duplicated rather than shared, and what would make that worth revisiting.

create table outbox (
  id           uuid primary key,                -- doubles as the eventId
  aggregate_id uuid        not null,            -- Kafka message key → ordering per withdrawal
  topic        text        not null,
  event_type   text        not null,
  payload      jsonb       not null,
  created_at   timestamptz not null default now(),
  published_at timestamptz                      -- null = not yet sent
);

-- Partial index: it holds only the backlog, so the relay's query stays on a
-- handful of rows however many million published ones accumulate behind them.
create index outbox_unpublished on outbox (created_at) where published_at is null;
