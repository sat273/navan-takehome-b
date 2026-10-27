package com.example.travel.service;

import com.example.travel.api.ApiException;
import com.example.travel.model.Invoice;
import com.example.travel.model.InvoiceSummary;
import com.example.travel.model.Page;
import com.example.travel.model.Transaction;
import com.example.travel.persistence.InvoiceRepository;
import com.example.travel.persistence.TransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InvoiceService {
  private final InvoiceRepository invoices;
  private final TransactionRepository transactions;
  private final InvoiceSnapshotBuilder builder;
  private final TransactionTemplate tx;

  public InvoiceService(
      InvoiceRepository invoices,
      TransactionRepository transactions,
      InvoiceSnapshotBuilder builder,
      TransactionTemplate tx) {
    this.invoices = invoices;
    this.transactions = transactions;
    this.builder = builder;
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

  // Hard delete, as the brief asks. A real billing system would void or credit instead.
  public void delete(String id) {
    if (!invoices.delete(id)) {
      throw notFound(id);
    }
  }

  /** Call inside a DB transaction so the transactions can't change before commit. */
  private Invoice snapshot(String id, List<String> ids, Instant createdAt, Instant updatedAt) {
    List<Transaction> txs = new ArrayList<>();
    for (String tid : ids) {
      txs.add(
          transactions
              .findById(tid)
              .orElseThrow(() -> ApiException.notFound("Transaction " + tid + " not found")));
    }
    return builder.build(id, txs, createdAt, updatedAt);
  }

  /** A repeated id is a 409, not silently deduped. */
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
