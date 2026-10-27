# Architecture

Layers: `api` (controllers, error mapping) -> `service` (TransactionService, InvoiceService: rules and DB transactions) -> `persistence` (JdbcTemplate repositories) ; `validation` (envelope checks plus one `MetadataRule` bean per type, so a new type is a new class); `model` (immutable records). `InvoiceSnapshotBuilder` is a stateless component holding the copy and rollup logic. All beans are stateless singletons, so there is no shared mutable state between request threads.

## external_id uniqueness and concurrent POSTs
`transactions.external_id` has a UNIQUE index. POST does a cheap lookup first (fast path for sequential duplicates), then inserts header + tax lines + fee lines + metadata in one DB transaction. If two POSTs race, the loser's INSERT violates the unique index, rolls back, and the service re-reads the winner and returns `409` with its id. No in-memory state, no locks beyond the DB. Covered by a test that fires 8 simultaneous POSTs x 5 rounds.

A lock timeout that still happens under extreme load returns `503` with `Retry-After`, not a 500. Race tests cover duplicate POSTs, PATCH vs invoice create, and DELETE vs invoice create.

SQLite runs with WAL, `busy_timeout=10s` and `transaction_mode=IMMEDIATE`, so write transactions queue on the write lock instead of failing on lock upgrade, and readers are never blocked.

## Tables and models
- `transactions`: header (id, external_id UNIQUE, type, currency, occurred_at, total_minor) + `metadata_json` (full type-specific payload, source of truth for GET round-trip) + queryable columns lifted from metadata: `origin`, `destination` (flight; rail first/last leg), `hotel_name`, `fee_kind` (navan_fee).
- `transaction_tax_lines(name, rate, amount_minor)` and `transaction_fee_lines(name, amount_minor)`: separate child tables, ON DELETE CASCADE.
- Type-specific data (flight `coupons` with `flight_number`, hotel `name` + `address`, rail `legs`, navan_fee `fee_kind` of `trip_fee` / `agent_call_fee`) is validated per type on write and stored in the JSON column. Chosen over per-type child tables to keep the schema small; the columns above cover the lookups we would index first. Trade-off: querying inside coupons/legs needs a JSON query or a future child table.

## Invoice snapshot
`invoice_lines` copies type, external_id, currency, occurred_at, total, tax/fee lines and metadata from the transaction at invoice time; it holds `transaction_id` only for the "is this billed" check, never for reading data. `invoice_tax_rollup` / `invoice_fee_rollup` are materialised at write time. Creating or PATCHing an invoice reads transactions and writes the snapshot in one DB transaction. Editing/deleting a billed transaction returns 409; additionally `invoice_lines.transaction_id` is an ON DELETE RESTRICT foreign key, so the database refuses the delete even if the service check were bypassed. Deleting an invoice cascades to its lines and rollups and leaves transactions alone.

## High volume
What breaks first: (1) concurrent writers contending on SQLite's single write lock, (2) duplicate-POST races, (3) unbounded lists, (4) the "is this billed" check scanning invoice lines.
- Contention: short IMMEDIATE transactions, WAL, busy timeout, pool of 8. If write throughput outgrows this, the same schema moves to Postgres (row locks, same unique index) with no model change.
- Duplicates: UNIQUE index on `external_id`, conflict handled, not pre-checked only.
- Lists: `LIMIT/OFFSET` in SQL, max 100, `has_more` via limit+1 (no COUNT(*)), tax/fee lines for a page loaded with two `IN` queries (no N+1). Offset paging gets slower on deep pages; keyset on `seq` is the next step.
- Indexes: `transactions(id)`, `transactions(external_id)` UNIQUE, `idx_invoice_lines_tx(transaction_id)`, indexes on every child-table foreign key.
