-- Flyway naming: V<version>__<description>.sql  (two underscores).
-- Flyway applies each file once, in version order, and records it in
-- flyway_schema_history. Migrations are immutable: once applied, you never edit
-- this file, you add V2__... instead. Editing it changes its checksum and Flyway
-- refuses to start.

-- ---------------------------------------------------------------------------
-- Ledger
-- ---------------------------------------------------------------------------

-- numeric(78,0): wei, as an integer with no fractional part. 78 digits covers
-- 2^256, the largest value Ethereum can represent. Never float — 0.1 + 0.2 is
-- not 0.3 in binary floating point, and this is money.
create table accounts (
  id         uuid primary key,
  client_id  uuid,                              -- null for system accounts
  type       text        not null,              -- CLIENT, PENDING_OUT, BANK_OPERATING, EXTERNAL
  asset      text        not null default 'ETH',
  balance    numeric(78,0) not null default 0,
  version    bigint      not null default 0,    -- for optimistic locking (M1)

  -- The last line of defence. Even with a bug in the ledger code, Postgres
  -- will not let a client account go negative. EXTERNAL is *expected* to go
  -- negative (it represents the outside world), hence the type check.
  constraint client_non_negative check (type <> 'CLIENT' or balance >= 0)
);

-- One row per business event that moves money. Its entries must sum to zero.
create table journal_transactions (
  id           uuid primary key,
  kind         text        not null,            -- DEPOSIT, WITHDRAWAL_HOLD, WITHDRAWAL_SETTLE, ...
  reference_id uuid        not null,            -- e.g. the withdrawal id
  created_at   timestamptz not null default now(),

  -- Idempotency at the ledger layer: "settle withdrawal X" can be booked at
  -- most once, however many times a retried Kafka message delivers it.
  unique (kind, reference_id)
);

-- Append-only. A mistake is corrected with a new reversing entry, never an
-- UPDATE or DELETE — the history is the audit trail.
create table journal_entries (
  id             bigserial primary key,
  transaction_id uuid          not null references journal_transactions(id),
  account_id     uuid          not null references accounts(id),
  amount         numeric(78,0) not null         -- signed: negative = debit, positive = credit
);

create index journal_entries_account on journal_entries (account_id);

-- ---------------------------------------------------------------------------
-- Withdrawals
-- ---------------------------------------------------------------------------

create table whitelisted_addresses (
  client_id uuid not null,
  address   text not null,                      -- lowercase 0x hex
  primary key (client_id, address)
);

create table withdrawals (
  id              uuid primary key,
  client_id       uuid          not null,
  account_id      uuid          not null references accounts(id),
  destination     text          not null,
  amount          numeric(78,0) not null check (amount > 0),
  status          text          not null,       -- see WithdrawalStatus enum
  idempotency_key text          not null,
  request_hash    text          not null,       -- SHA-256 of the request body
  tx_hash         text,
  failure_reason  text,
  version         bigint        not null default 0,
  created_at      timestamptz   not null default now(),
  updated_at      timestamptz   not null default now(),

  -- The idempotency guarantee. Two identical requests racing each other: one
  -- INSERT wins, the other gets a unique violation and we return the original.
  unique (client_id, idempotency_key)
);

create index withdrawals_status on withdrawals (status);

-- ---------------------------------------------------------------------------
-- Four-eyes approvals
-- ---------------------------------------------------------------------------

create table approvers (
  id         uuid primary key,
  name       text  not null,
  public_key bytea not null                     -- Ed25519, 32 bytes
);

create table approvals (
  withdrawal_id uuid        not null references withdrawals(id),
  approver_id   uuid        not null references approvers(id),
  signature     bytea       not null,           -- Ed25519, 64 bytes
  created_at    timestamptz not null default now(),

  -- One approval per approver per withdrawal: quorum can't be faked by one
  -- person submitting twice.
  primary key (withdrawal_id, approver_id)
);

-- ---------------------------------------------------------------------------
-- Transactional outbox
-- ---------------------------------------------------------------------------

-- Solves the dual-write problem: the state change and the "publish this event"
-- intent are written in ONE database transaction, so they cannot diverge.
-- A relay then moves rows to Kafka. See C6.
create table outbox (
  id           uuid primary key,                -- doubles as the eventId
  aggregate_id uuid        not null,            -- Kafka message key → ordering per withdrawal
  topic        text        not null,
  event_type   text        not null,
  payload      jsonb       not null,
  created_at   timestamptz not null default now(),
  published_at timestamptz                      -- null = not yet sent
);

-- A partial index: it only contains unpublished rows, so it stays tiny however
-- large the outbox grows. The relay's query hits exactly this index.
create index outbox_unpublished on outbox (created_at) where published_at is null;

-- ---------------------------------------------------------------------------
-- Idempotent consumers
-- ---------------------------------------------------------------------------

-- Kafka delivery is at-least-once, so every consumer must tolerate duplicates.
-- Inserting (consumer, event_id) in the same transaction as the state change
-- makes "handle this event" exactly-once in effect.
create table processed_events (
  consumer     text        not null,
  event_id     uuid        not null,
  processed_at timestamptz not null default now(),
  primary key (consumer, event_id)
);
