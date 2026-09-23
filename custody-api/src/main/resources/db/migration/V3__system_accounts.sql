-- The three accounts that belong to nobody.
--
-- A double-entry posting needs an account on both sides, so "money arrived from
-- the outside world" and "money is reserved pending a withdrawal" each need a
-- real row to point at. They are singletons: there is exactly one EXTERNAL, and
-- every client's withdrawal holds sit in the same PENDING_OUT.
--
-- Seeded here, with literal ids, rather than found-or-created on startup. Two
-- reasons. A find-or-create runs on every boot and races itself when two
-- instances start together, which would be a second EXTERNAL account and a
-- ledger that no longer adds up. And a fixed id can be a constant in Java
-- (SystemAccounts), so the code that posts a hold names the account instead of
-- querying for it.
--
-- The ids are deliberately obvious rather than random: anyone reading a journal
-- entry in psql can see at a glance that ...0002 is not somebody's client
-- account.
insert into accounts (id, client_id, type, asset, balance, version) values
  ('00000000-0000-0000-0000-000000000001', null, 'EXTERNAL',       'ETH', 0, 0),
  ('00000000-0000-0000-0000-000000000002', null, 'PENDING_OUT',    'ETH', 0, 0),
  ('00000000-0000-0000-0000-000000000003', null, 'BANK_OPERATING', 'ETH', 0, 0);
