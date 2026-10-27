CREATE TABLE IF NOT EXISTS transactions (
  seq            INTEGER PRIMARY KEY AUTOINCREMENT,
  id             TEXT    NOT NULL UNIQUE,
  external_id    TEXT    NOT NULL UNIQUE,
  type           TEXT    NOT NULL CHECK (type IN ('flight','hotel','rail','navan_fee')),
  currency       TEXT    NOT NULL,
  occurred_at    TEXT    NOT NULL,
  total_minor    INTEGER NOT NULL,
  origin         TEXT,
  destination    TEXT,
  hotel_name     TEXT,
  fee_kind       TEXT,
  metadata_json  TEXT    NOT NULL,
  created_at     TEXT    NOT NULL,
  updated_at     TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS transaction_tax_lines (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  transaction_seq INTEGER NOT NULL REFERENCES transactions(seq) ON DELETE CASCADE,
  position        INTEGER NOT NULL,
  name            TEXT    NOT NULL,
  rate            TEXT,
  amount_minor    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tx_tax_lines_tx ON transaction_tax_lines(transaction_seq);

CREATE TABLE IF NOT EXISTS transaction_fee_lines (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  transaction_seq INTEGER NOT NULL REFERENCES transactions(seq) ON DELETE CASCADE,
  position        INTEGER NOT NULL,
  name            TEXT    NOT NULL,
  amount_minor    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tx_fee_lines_tx ON transaction_fee_lines(transaction_seq);

CREATE TABLE IF NOT EXISTS invoices (
  seq          INTEGER PRIMARY KEY AUTOINCREMENT,
  id           TEXT    NOT NULL UNIQUE,
  currency     TEXT    NOT NULL,
  total_minor  INTEGER NOT NULL,
  created_at   TEXT    NOT NULL,
  updated_at   TEXT    NOT NULL
);

-- Snapshot rows. The RESTRICT key makes the DB itself refuse to delete a billed transaction.
CREATE TABLE IF NOT EXISTS invoice_lines (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  invoice_seq    INTEGER NOT NULL REFERENCES invoices(seq) ON DELETE CASCADE,
  position       INTEGER NOT NULL,
  transaction_id TEXT    NOT NULL REFERENCES transactions(id) ON DELETE RESTRICT,
  type           TEXT    NOT NULL,
  external_id    TEXT    NOT NULL,
  currency       TEXT    NOT NULL,
  occurred_at    TEXT    NOT NULL,
  total_minor    INTEGER NOT NULL,
  tax_lines_json TEXT    NOT NULL,
  fee_lines_json TEXT    NOT NULL,
  metadata_json  TEXT    NOT NULL,
  UNIQUE (invoice_seq, transaction_id)
);
CREATE INDEX IF NOT EXISTS idx_invoice_lines_tx ON invoice_lines(transaction_id);

CREATE TABLE IF NOT EXISTS invoice_tax_rollup (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  invoice_seq  INTEGER NOT NULL REFERENCES invoices(seq) ON DELETE CASCADE,
  position     INTEGER NOT NULL,
  name         TEXT    NOT NULL,
  rate         TEXT,
  amount_minor INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_invoice_tax_rollup_inv ON invoice_tax_rollup(invoice_seq);

CREATE TABLE IF NOT EXISTS invoice_fee_rollup (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  invoice_seq  INTEGER NOT NULL REFERENCES invoices(seq) ON DELETE CASCADE,
  position     INTEGER NOT NULL,
  name         TEXT    NOT NULL,
  amount_minor INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_invoice_fee_rollup_inv ON invoice_fee_rollup(invoice_seq);
