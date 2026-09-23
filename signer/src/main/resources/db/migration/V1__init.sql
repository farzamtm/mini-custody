-- The signer's schema lives in its own database. In production it would also
-- be its own database *user*, with no grants on the custody tables and vice
-- versa: least privilege, so a SQL injection in the API cannot read key material.

-- ---------------------------------------------------------------------------
-- Envelope-encrypted wallet keys
-- ---------------------------------------------------------------------------

-- The private key is never stored in the clear and never encrypted directly
-- with the master key. Instead:
--   master key (env var here, a KMS/HSM in production)
--     wraps  -> data key (one per wallet, random)
--       encrypts -> the Ethereum private key
-- Rotating the master key then means re-wrapping a handful of small data keys,
-- not re-encrypting everything. See C7.
create table wallet_keys (
  address               text  primary key,      -- lowercase 0x hex
  encrypted_private_key bytea not null,         -- AES-256-GCM ciphertext
  key_iv                bytea not null,         -- 12-byte GCM nonce, never reused with one key
  wrapped_data_key      bytea not null          -- the data key, encrypted by the master key
);

-- ---------------------------------------------------------------------------
-- Ethereum transaction nonces
-- ---------------------------------------------------------------------------

-- The *transaction* nonce: a public per-account counter 0, 1, 2, ... that orders
-- transactions and prevents replay. Not to be confused with the ECDSA signing
-- nonce `k`, which is secret and leaks the private key if reused (C7).
--
-- We reserve a nonce with SELECT ... FOR UPDATE inside the signing transaction,
-- so two concurrent withdrawals can never take the same one.
create table chain_nonces (
  address    text   primary key,
  next_nonce bigint not null
);

-- ---------------------------------------------------------------------------
-- Signing log
-- ---------------------------------------------------------------------------

-- withdrawal_id is the PRIMARY KEY, which is the whole safety property: a
-- withdrawal can be signed at most once, even if the same WithdrawalApproved
-- event is delivered twice. The raw transaction is kept so a failed broadcast
-- can be retried by re-sending the identical bytes — re-signing with a new
-- nonce could get both mined and pay out twice.
create table signing_log (
  withdrawal_id uuid        primary key,
  tx_hash       text        not null,
  raw_tx        text        not null,
  nonce         bigint      not null,
  signed_at     timestamptz not null default now(),
  broadcast_at  timestamptz                     -- null = signed but not yet on the wire
);

-- ---------------------------------------------------------------------------
-- Idempotent consumer
-- ---------------------------------------------------------------------------

create table processed_events (
  consumer     text        not null,
  event_id     uuid        not null,
  processed_at timestamptz not null default now(),
  primary key (consumer, event_id)
);
