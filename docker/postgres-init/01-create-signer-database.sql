-- Runs once, on first startup of an empty Postgres data directory.
-- POSTGRES_DB already created "custody"; the signer gets its own database so
-- the two services cannot read each other's tables.
create database signer;
