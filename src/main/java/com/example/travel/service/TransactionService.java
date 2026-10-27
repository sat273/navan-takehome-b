package com.example.travel.service;

import com.example.travel.api.ApiException;
import com.example.travel.model.Page;
import com.example.travel.model.Transaction;
import com.example.travel.persistence.InvoiceRepository;
import com.example.travel.persistence.TransactionRepository;
import com.example.travel.validation.MetadataSummary;
import com.example.travel.validation.TransactionInput;
import com.example.travel.validation.TransactionPatch;
import com.example.travel.validation.TransactionValidator;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransactionService {
  private final TransactionRepository transactions;
  private final InvoiceRepository invoices;
  private final TransactionValidator validator;
  private final TransactionTemplate tx;

  public TransactionService(
      TransactionRepository transactions,
      InvoiceRepository invoices,
      TransactionValidator validator,
      TransactionTemplate tx) {
    this.transactions = transactions;
    this.invoices = invoices;
    this.validator = validator;
    this.tx = tx;
  }

  /**
   * Duplicate identity is enforced by the UNIQUE index on external_id. The up-front lookup only
   * makes the common sequential duplicate cheap; when two POSTs race, the loser's INSERT fails,
   * its transaction rolls back, and we re-read the winner to report its id.
   */
  public Transaction create(JsonNode body) {
    TransactionInput in = validator.parseEnvelope(body);
    transactions.findByExternalId(in.externalId()).ifPresent(this::duplicate);
    MetadataSummary summary = validator.validateMetadata(in.type(), in.metadata());

    Instant now = Instant.now();
    Transaction t =
        new Transaction(
            "txn_" + UUID.randomUUID().toString().replace("-", ""),
            in.type(),
            in.externalId(),
            in.currency(),
            in.occurredAt(),
            in.total(),
            in.taxLines(),
            in.feeLines(),
            in.metadata(),
            now,
            now);
    try {
      tx.executeWithoutResult(s -> transactions.insert(t, summary));
    } catch (DataAccessException e) {
      Optional<Transaction> winner = transactions.findByExternalId(in.externalId());
      if (winner.isPresent()) {
        duplicate(winner.get());
      }
      throw e;
    }
    return transactions.findById(t.id()).orElseThrow();
  }

  public Transaction get(String id) {
    return transactions.findById(id).orElseThrow(() -> notFound(id));
  }

  public Page<Transaction> list(int limit, int offset) {
    return transactions.list(limit, offset);
  }

  public Transaction patch(String id, JsonNode body) {
    TransactionPatch p = validator.parsePatch(body);
    tx.executeWithoutResult(
        s -> {
          Transaction cur = transactions.findById(id).orElseThrow(() -> notFound(id));
          if (invoices.isTransactionBilled(id)) {
            throw ApiException.conflict(
                "transaction_invoiced", "Transaction " + id + " is on an invoice and cannot be modified");
          }
          JsonNode metadata = p.metadata() != null ? p.metadata() : cur.metadata();
          MetadataSummary summary = validator.validateMetadata(cur.type(), metadata);
          Transaction next =
              new Transaction(
                  cur.id(),
                  cur.type(),
                  cur.externalId(),
                  cur.currency(),
                  p.occurredAt() != null ? p.occurredAt() : cur.occurredAt(),
                  p.total() != null ? p.total() : cur.total(),
                  p.taxLines() != null ? p.taxLines() : cur.taxLines(),
                  p.feeLines() != null ? p.feeLines() : cur.feeLines(),
                  metadata,
                  cur.createdAt(),
                  Instant.now());
          transactions.update(next, summary, p.taxLines() != null, p.feeLines() != null);
        });
    return get(id);
  }

  public void delete(String id) {
    tx.executeWithoutResult(
        s -> {
          if (transactions.findById(id).isEmpty()) {
            throw notFound(id);
          }
          if (invoices.isTransactionBilled(id)) {
            throw ApiException.conflict(
                "transaction_invoiced", "Transaction " + id + " is on an invoice and cannot be deleted");
          }
          transactions.delete(id);
        });
  }

  private void duplicate(Transaction existing) {
    throw new ApiException(
        HttpStatus.CONFLICT,
        "duplicate_external_id",
        "A transaction with external_id '" + existing.externalId() + "' already exists",
        Map.of("id", existing.id(), "existing_id", existing.id()));
  }

  private ApiException notFound(String id) {
    return ApiException.notFound("Transaction " + id + " not found");
  }
}
