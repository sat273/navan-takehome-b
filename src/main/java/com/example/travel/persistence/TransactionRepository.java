package com.example.travel.persistence;

import com.example.travel.model.FeeLine;
import com.example.travel.model.Money;
import com.example.travel.model.Page;
import com.example.travel.model.TaxLine;
import com.example.travel.model.Transaction;
import com.example.travel.validation.MetadataSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {
  private static final String COLUMNS =
      "seq, id, type, external_id, currency, occurred_at, total_minor, metadata_json, created_at, updated_at";

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public TransactionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  private record Row(long seq, Transaction base) {}

  public void insert(Transaction t, MetadataSummary s) {
    jdbc.update(
        "INSERT INTO transactions (id, external_id, type, currency, occurred_at, total_minor,"
            + " origin, destination, hotel_name, fee_kind, metadata_json, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        t.id(),
        t.externalId(),
        t.type(),
        t.currency(),
        t.occurredAt().toString(),
        Money.toMinor(t.total()),
        s.origin(),
        s.destination(),
        s.hotelName(),
        s.feeKind(),
        json(t.metadata()),
        t.createdAt().toString(),
        t.updatedAt().toString());
    long seq = seqOf(t.id());
    insertTaxLines(seq, t.taxLines());
    insertFeeLines(seq, t.feeLines());
  }

  public void update(Transaction t, MetadataSummary s, boolean replaceTax, boolean replaceFees) {
    jdbc.update(
        "UPDATE transactions SET occurred_at=?, total_minor=?, origin=?, destination=?, hotel_name=?,"
            + " fee_kind=?, metadata_json=?, updated_at=? WHERE id=?",
        t.occurredAt().toString(),
        Money.toMinor(t.total()),
        s.origin(),
        s.destination(),
        s.hotelName(),
        s.feeKind(),
        json(t.metadata()),
        t.updatedAt().toString(),
        t.id());
    long seq = seqOf(t.id());
    if (replaceTax) {
      jdbc.update("DELETE FROM transaction_tax_lines WHERE transaction_seq=?", seq);
      insertTaxLines(seq, t.taxLines());
    }
    if (replaceFees) {
      jdbc.update("DELETE FROM transaction_fee_lines WHERE transaction_seq=?", seq);
      insertFeeLines(seq, t.feeLines());
    }
  }

  public boolean delete(String id) {
    return jdbc.update("DELETE FROM transactions WHERE id=?", id) > 0;
  }

  public Optional<Transaction> findById(String id) {
    return one("SELECT " + COLUMNS + " FROM transactions WHERE id=?", id);
  }

  public Optional<Transaction> findByExternalId(String externalId) {
    return one("SELECT " + COLUMNS + " FROM transactions WHERE external_id=?", externalId);
  }

  public Page<Transaction> list(int limit, int offset) {
    List<Row> rows =
        jdbc.query(
            "SELECT " + COLUMNS + " FROM transactions ORDER BY seq DESC LIMIT ? OFFSET ?",
            (rs, i) -> row(rs),
            limit + 1,
            offset);
    boolean hasMore = rows.size() > limit;
    return new Page<>(hydrate(hasMore ? rows.subList(0, limit) : rows), limit, offset, hasMore);
  }

  private Optional<Transaction> one(String sql, String arg) {
    List<Row> rows = jdbc.query(sql, (rs, i) -> row(rs), arg);
    return hydrate(rows).stream().findFirst();
  }

  private long seqOf(String id) {
    return jdbc.queryForObject("SELECT seq FROM transactions WHERE id=?", Long.class, id);
  }

  private Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Row(
        rs.getLong("seq"),
        new Transaction(
            rs.getString("id"),
            rs.getString("type"),
            rs.getString("external_id"),
            rs.getString("currency"),
            Instant.parse(rs.getString("occurred_at")),
            Money.fromMinor(rs.getLong("total_minor")),
            List.of(),
            List.of(),
            parse(rs.getString("metadata_json")),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))));
  }

  /** One query each for tax and fee lines, so no N+1. */
  private List<Transaction> hydrate(List<Row> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    String in = "(" + String.join(",", rows.stream().map(r -> String.valueOf(r.seq())).toList()) + ")";
    Map<Long, List<TaxLine>> taxes = new HashMap<>();
    jdbc.query(
        "SELECT transaction_seq, name, rate, amount_minor FROM transaction_tax_lines"
            + " WHERE transaction_seq IN " + in + " ORDER BY transaction_seq, position",
        rs -> {
          String rate = rs.getString("rate");
          taxes
              .computeIfAbsent(rs.getLong("transaction_seq"), k -> new ArrayList<>())
              .add(
                  new TaxLine(
                      rs.getString("name"),
                      rate == null ? null : new BigDecimal(rate),
                      Money.fromMinor(rs.getLong("amount_minor"))));
        });
    Map<Long, List<FeeLine>> fees = new HashMap<>();
    jdbc.query(
        "SELECT transaction_seq, name, amount_minor FROM transaction_fee_lines"
            + " WHERE transaction_seq IN " + in + " ORDER BY transaction_seq, position",
        rs -> {
          fees.computeIfAbsent(rs.getLong("transaction_seq"), k -> new ArrayList<>())
              .add(new FeeLine(rs.getString("name"), Money.fromMinor(rs.getLong("amount_minor"))));
        });
    return rows.stream()
        .map(
            r -> {
              Transaction b = r.base();
              return new Transaction(
                  b.id(),
                  b.type(),
                  b.externalId(),
                  b.currency(),
                  b.occurredAt(),
                  b.total(),
                  taxes.getOrDefault(r.seq(), List.of()),
                  fees.getOrDefault(r.seq(), List.of()),
                  b.metadata(),
                  b.createdAt(),
                  b.updatedAt());
            })
        .toList();
  }

  private void insertTaxLines(long seq, List<TaxLine> lines) {
    for (int i = 0; i < lines.size(); i++) {
      TaxLine l = lines.get(i);
      jdbc.update(
          "INSERT INTO transaction_tax_lines (transaction_seq, position, name, rate, amount_minor)"
              + " VALUES (?,?,?,?,?)",
          seq,
          i,
          l.name(),
          l.rate() == null ? null : l.rate().toPlainString(),
          Money.toMinor(l.amount()));
    }
  }

  private void insertFeeLines(long seq, List<FeeLine> lines) {
    for (int i = 0; i < lines.size(); i++) {
      FeeLine l = lines.get(i);
      jdbc.update(
          "INSERT INTO transaction_fee_lines (transaction_seq, position, name, amount_minor)"
              + " VALUES (?,?,?,?)",
          seq,
          i,
          l.name(),
          Money.toMinor(l.amount()));
    }
  }

  private String json(JsonNode n) {
    try {
      return mapper.writeValueAsString(n);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode parse(String s) {
    try {
      return mapper.readTree(s);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
