package com.example.travel.persistence;

import com.example.travel.model.FeeLine;
import com.example.travel.model.Invoice;
import com.example.travel.model.InvoiceLine;
import com.example.travel.model.InvoiceSummary;
import com.example.travel.model.Money;
import com.example.travel.model.Page;
import com.example.travel.model.TaxLine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InvoiceRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public InvoiceRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public boolean isTransactionBilled(String transactionId) {
    return !jdbc.queryForList(
            "SELECT 1 FROM invoice_lines WHERE transaction_id=? LIMIT 1", transactionId)
        .isEmpty();
  }

  public void insert(Invoice inv) {
    jdbc.update(
        "INSERT INTO invoices (id, currency, total_minor, created_at, updated_at) VALUES (?,?,?,?,?)",
        inv.id(),
        inv.currency(),
        Money.toMinor(inv.total()),
        inv.createdAt().toString(),
        inv.updatedAt().toString());
    writeContents(seqOf(inv.id()), inv);
  }

  /** Caller wraps this in a DB transaction. */
  public void replaceContents(Invoice inv) {
    long seq = seqOf(inv.id());
    jdbc.update(
        "UPDATE invoices SET currency=?, total_minor=?, updated_at=? WHERE seq=?",
        inv.currency(),
        Money.toMinor(inv.total()),
        inv.updatedAt().toString(),
        seq);
    jdbc.update("DELETE FROM invoice_lines WHERE invoice_seq=?", seq);
    jdbc.update("DELETE FROM invoice_tax_rollup WHERE invoice_seq=?", seq);
    jdbc.update("DELETE FROM invoice_fee_rollup WHERE invoice_seq=?", seq);
    writeContents(seq, inv);
  }

  public boolean delete(String id) {
    return jdbc.update("DELETE FROM invoices WHERE id=?", id) > 0;
  }

  public Optional<Invoice> findById(String id) {
    List<Object[]> head =
        jdbc.query(
            "SELECT seq, id, currency, total_minor, created_at, updated_at FROM invoices WHERE id=?",
            (rs, i) ->
                new Object[] {
                  rs.getLong("seq"),
                  rs.getString("id"),
                  rs.getString("currency"),
                  rs.getLong("total_minor"),
                  rs.getString("created_at"),
                  rs.getString("updated_at")
                },
            id);
    if (head.isEmpty()) {
      return Optional.empty();
    }
    Object[] h = head.get(0);
    long seq = (Long) h[0];
    List<InvoiceLine> lines =
        jdbc.query(
            "SELECT transaction_id, type, external_id, currency, occurred_at, total_minor,"
                + " tax_lines_json, fee_lines_json, metadata_json FROM invoice_lines"
                + " WHERE invoice_seq=? ORDER BY position",
            (rs, i) ->
                new InvoiceLine(
                    rs.getString("transaction_id"),
                    rs.getString("type"),
                    rs.getString("external_id"),
                    rs.getString("currency"),
                    Instant.parse(rs.getString("occurred_at")),
                    Money.fromMinor(rs.getLong("total_minor")),
                    read(rs.getString("tax_lines_json"), new TypeReference<List<TaxLine>>() {}),
                    read(rs.getString("fee_lines_json"), new TypeReference<List<FeeLine>>() {}),
                    read(rs.getString("metadata_json"), new TypeReference<>() {})),
            seq);
    List<TaxLine> tax =
        jdbc.query(
            "SELECT name, rate, amount_minor FROM invoice_tax_rollup WHERE invoice_seq=? ORDER BY position",
            (rs, i) -> {
              String rate = rs.getString("rate");
              return new TaxLine(
                  rs.getString("name"),
                  rate == null ? null : new BigDecimal(rate),
                  Money.fromMinor(rs.getLong("amount_minor")));
            },
            seq);
    List<FeeLine> fees =
        jdbc.query(
            "SELECT name, amount_minor FROM invoice_fee_rollup WHERE invoice_seq=? ORDER BY position",
            (rs, i) -> new FeeLine(rs.getString("name"), Money.fromMinor(rs.getLong("amount_minor"))),
            seq);
    return Optional.of(
        new Invoice(
            (String) h[1],
            (String) h[2],
            Money.fromMinor((Long) h[3]),
            lines,
            tax,
            fees,
            Instant.parse((String) h[4]),
            Instant.parse((String) h[5])));
  }

  public Page<InvoiceSummary> list(int limit, int offset) {
    List<InvoiceSummary> rows =
        jdbc.query(
            "SELECT i.id, i.currency, i.total_minor, i.created_at,"
                + " (SELECT COUNT(*) FROM invoice_lines l WHERE l.invoice_seq = i.seq) AS line_count"
                + " FROM invoices i ORDER BY i.seq DESC LIMIT ? OFFSET ?",
            (rs, i) ->
                new InvoiceSummary(
                    rs.getString("id"),
                    rs.getString("currency"),
                    Money.fromMinor(rs.getLong("total_minor")),
                    rs.getInt("line_count"),
                    Instant.parse(rs.getString("created_at"))),
            limit + 1,
            offset);
    boolean hasMore = rows.size() > limit;
    return new Page<>(hasMore ? rows.subList(0, limit) : rows, limit, offset, hasMore);
  }

  private long seqOf(String id) {
    return jdbc.queryForObject("SELECT seq FROM invoices WHERE id=?", Long.class, id);
  }

  private void writeContents(long seq, Invoice inv) {
    for (int i = 0; i < inv.lines().size(); i++) {
      InvoiceLine l = inv.lines().get(i);
      jdbc.update(
          "INSERT INTO invoice_lines (invoice_seq, position, transaction_id, type, external_id, currency,"
              + " occurred_at, total_minor, tax_lines_json, fee_lines_json, metadata_json)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
          seq,
          i,
          l.transactionId(),
          l.type(),
          l.externalId(),
          l.currency(),
          l.occurredAt().toString(),
          Money.toMinor(l.total()),
          write(l.taxLines()),
          write(l.feeLines()),
          write(l.metadata()));
    }
    for (int i = 0; i < inv.taxRollup().size(); i++) {
      TaxLine t = inv.taxRollup().get(i);
      jdbc.update(
          "INSERT INTO invoice_tax_rollup (invoice_seq, position, name, rate, amount_minor) VALUES (?,?,?,?,?)",
          seq,
          i,
          t.name(),
          t.rate() == null ? null : t.rate().toPlainString(),
          Money.toMinor(t.amount()));
    }
    for (int i = 0; i < inv.feeRollup().size(); i++) {
      FeeLine f = inv.feeRollup().get(i);
      jdbc.update(
          "INSERT INTO invoice_fee_rollup (invoice_seq, position, name, amount_minor) VALUES (?,?,?,?)",
          seq,
          i,
          f.name(),
          Money.toMinor(f.amount()));
    }
  }

  private String write(Object o) {
    try {
      return mapper.writeValueAsString(o);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private <T> T read(String s, TypeReference<T> type) {
    try {
      return mapper.readValue(s, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
