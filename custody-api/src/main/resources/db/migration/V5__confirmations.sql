-- Numbered V5, not V4, on purpose.
--
-- M3 is in flight on its own branch and owns V4. Two branches both writing V4 is
-- a merge conflict at best; at worst somebody applies one of them, the other
-- lands, and Flyway refuses to start because the checksum it recorded for V4 is
-- not the checksum of the file now on disk. Flyway does not require contiguous
-- version numbers, so reserving V4 costs nothing and removes the collision.

-- ---------------------------------------------------------------------------
-- What the chain said
-- ---------------------------------------------------------------------------

-- One row per withdrawal that has been mined, written by the confirmation
-- watcher and then re-checked on every tick until it settles.
--
-- Why store it at all, rather than polling and acting in one step. Three reasons,
-- and only the first is obvious. It is the evidence behind a settlement, so
-- reconciliation can ask "what did we think we saw" separately from "what does
-- the chain say now" — comparing the chain against itself proves nothing. It lets
-- the API report a confirmation count without an RPC call per request. And it is
-- what makes a reorg visible: a stored receipt whose transaction the chain no
-- longer has is a specific, detectable event, whereas a watcher with no memory
-- would see only a transaction that had gone quiet again.
create table transaction_receipts (
  withdrawal_id       uuid          primary key references withdrawals(id),
  tx_hash             text          not null,
  block_number        bigint        not null,
  -- 1 = the transaction did what was asked, 0 = it reverted. Both are mined and
  -- both were charged a fee; only the first moved any money.
  succeeded           boolean       not null,
  gas_used            numeric(78,0) not null,
  effective_gas_price numeric(78,0) not null,
  -- How deep the receipt was when last looked at, so GET /v1/withdrawals/{id} can
  -- answer "2 of 3" without a JSON-RPC call behind every request. Denormalised on
  -- purpose: it is derivable from block_number and the chain head, and fetching
  -- the head per request is exactly the cost this avoids. It stops moving once the
  -- withdrawal settles, because the watcher only looks at BROADCAST rows — at
  -- which point it means "how deep it was when we settled", which is the number
  -- worth keeping anyway.
  confirmations       integer       not null,
  observed_at         timestamptz   not null default now()
);

-- ---------------------------------------------------------------------------
-- The bank's operating float
-- ---------------------------------------------------------------------------

-- Gas is paid by the custodian, not by the client, so settling a withdrawal books
-- BANK_OPERATING -fee / EXTERNAL +fee. V2's accounts_non_negative constraint
-- means that fails on the first withdrawal unless BANK_OPERATING holds something,
-- and it should: an operating account that goes negative is a bank that has spent
-- money it never recorded receiving, which is exactly what the constraint is for.
--
-- So the float is booked here, as a real journal transaction rather than by
-- setting a balance. `accounts.balance` is a cache of the journal and nothing
-- outside LedgerService.post may contradict it; a migration that wrote the column
-- alone would leave recomputedBalanceOf disagreeing with balanceOf on the very
-- first assertion anybody made about it.
--
-- 10 ETH, which at Anvil's gas prices is effectively unlimited and on a real
-- chain would be a fortnight's gas. In production this arrives when the treasury
-- funds the hot wallet, and the entry would be written by whatever observes that
-- happening — the same deposit watcher this project does not have.
insert into journal_transactions (id, kind, reference_id) values
  ('00000000-0000-0000-0000-00000000f10a', 'OPERATING_FLOAT', '00000000-0000-0000-0000-00000000f10a');

insert into journal_entries (transaction_id, account_id, amount) values
  ('00000000-0000-0000-0000-00000000f10a', '00000000-0000-0000-0000-000000000001', -10000000000000000000),
  ('00000000-0000-0000-0000-00000000f10a', '00000000-0000-0000-0000-000000000003',  10000000000000000000);

update accounts set balance = balance - 10000000000000000000
  where id = '00000000-0000-0000-0000-000000000001';
update accounts set balance = balance + 10000000000000000000
  where id = '00000000-0000-0000-0000-000000000003';
