package com.example.travel.service;

import com.example.travel.api.ApiException;
import com.example.travel.model.FeeLine;
import com.example.travel.model.Invoice;
import com.example.travel.model.InvoiceLine;
import com.example.travel.model.InvoiceSummary;
import com.example.travel.model.Page;
import com.example.travel.model.TaxLine;
import com.example.travel.model.Transaction;
import com.example.travel.persistence.InvoiceRepository;
import com.example.travel.persistence.TransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InvoiceService {
  private final InvoiceRepository invoices;
  private final TransactionRepository transactions;
  private final TransactionTemplate tx;

  public InvoiceService(
      InvoiceRepository invoices, TransactionRepository transactions, TransactionTemplate tx) {
    this.invoices = invoices;
    this.transactions = transactions;
    this.tx = tx;
  }

  public Invoice create(JsonNode body) {
    List<String> ids = parseIds(body);
    String id = "inv_" + UUID.randomUUID().toString().replace("-", "");
    tx.executeWithoutResult(s -> invoices.insert(snapshot(id, ids, Instant.now(), Instant.now())));
    return get(id);
  }

  public Invoice replace(String id, JsonNode body) {
    List<String> ids = parseIds(body);
    tx.executeWithoutResult(
        s -> {
          Invoice cur = invoices.findById(id).orElseThrow(() -> notFound(id));
          invoices.replaceContents(snapshot(id, ids, cur.createdAt(), Instant.now()));
        });
    return get(id);
  }

  public Invoice get(String id) {
    return invoices.findById(id).orElseThrow(() -> notFound(id));
  }

  public Page<InvoiceSummary> list(int limit, int offset) {
    return invoices.list(limit, offset);
  }

  public void delete(String id) {
    if (!invoices.delete(id)) {
      throw notFound(id);
    }
  }

  /** Must run inside the caller's DB transaction so the transactions read here cannot change before commit. */
  private Invoice snapshot(String id, List<String> ids, Instant createdAt, Instant updatedAt) {
    List<Transaction> txs = new ArrayList<>();
    for (String tid : ids) {
      txs.add(
          transactions
              .findById(tid)
              .orElseThrow(() -> ApiException.notFound("Transaction " + tid + " not found")));
    }
    String currency = txs.get(0).currency();
    for (Transaction t : txs) {
      if (!t.currency().equals(currency)) {
        throw ApiException.conflict(
            "mixed_currency",
            "Invoice cannot mix currencies: " + currency + " and " + t.currency());
      }
    }

    BigDecimal total = BigDecimal.ZERO;
    List<InvoiceLine> lines = new ArrayList<>();
    for (Transaction t : txs) {
      total = total.add(t.total());
      lines.add(
          new InvoiceLine(
              t.id(),
              t.type(),
              t.externalId(),
              t.currency(),
              t.occurredAt(),
              t.total(),
              t.taxLines(),
              t.feeLines(),
              t.metadata()));
    }
    return new Invoice(
        id,
        currency,
        total.setScale(2),
        lines,
        taxRollup(txs),
        feeRollup(txs),
        createdAt,
        updatedAt);
  }

  /** Tax lines are grouped by (name, rate); the rate is compared numerically so 0.07 equals 0.070. */
  private List<TaxLine> taxRollup(List<Transaction> txs) {
    Map<String, TaxLine> grouped = new LinkedHashMap<>();
    for (Transaction t : txs) {
      for (TaxLine l : t.taxLines()) {
        String key = l.name() + "|" + (l.rate() == null ? "" : l.rate().stripTrailingZeros().toPlainString());
        grouped.merge(
            key, l, (a, b) -> new TaxLine(a.name(), a.rate(), a.amount().add(b.amount())));
      }
    }
    return List.copyOf(grouped.values());
  }

  private List<FeeLine> feeRollup(List<Transaction> txs) {
    Map<String, FeeLine> grouped = new LinkedHashMap<>();
    for (Transaction t : txs) {
      for (FeeLine l : t.feeLines()) {
        grouped.merge(l.name(), l, (a, b) -> new FeeLine(a.name(), a.amount().add(b.amount())));
      }
    }
    return List.copyOf(grouped.values());
  }

  /** A transaction id listed twice is rejected with 409 rather than deduped, so nothing is silently dropped. */
  private List<String> parseIds(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw ApiException.badRequest("request body must be a JSON object");
    }
    boolean hasOne = body.has("transaction_id");
    boolean hasMany = body.has("transaction_ids");
    if (hasOne == hasMany) {
      throw ApiException.badRequest("Provide exactly one of transaction_id or transaction_ids");
    }
    List<String> ids = new ArrayList<>();
    if (hasOne) {
      ids.add(idValue(body.get("transaction_id")));
    } else {
      JsonNode arr = body.get("transaction_ids");
      if (!arr.isArray() || arr.isEmpty()) {
        throw ApiException.badRequest("transaction_ids must be a non-empty array");
      }
      arr.forEach(n -> ids.add(idValue(n)));
    }
    Set<String> seen = new HashSet<>();
    for (String id : ids) {
      if (!seen.add(id)) {
        throw ApiException.conflict(
            "duplicate_transaction_id", "Transaction " + id + " is listed more than once");
      }
    }
    return ids;
  }

  private String idValue(JsonNode n) {
    if (n == null || !n.isTextual() || n.asText().isBlank()) {
      throw ApiException.badRequest("transaction ids must be non-blank strings");
    }
    return n.asText();
  }

  private ApiException notFound(String id) {
    return ApiException.notFound("Invoice " + id + " not found");
  }
}
