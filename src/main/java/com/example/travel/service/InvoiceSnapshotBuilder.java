package com.example.travel.service;

import com.example.travel.api.ApiException;
import com.example.travel.model.FeeLine;
import com.example.travel.model.Invoice;
import com.example.travel.model.InvoiceLine;
import com.example.travel.model.TaxLine;
import com.example.travel.model.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Copies transactions into an invoice. No I/O, no state. */
@Component
public class InvoiceSnapshotBuilder {

  public Invoice build(String id, List<Transaction> txs, Instant createdAt, Instant updatedAt) {
    String currency = txs.get(0).currency();
    BigDecimal total = BigDecimal.ZERO;
    for (Transaction t : txs) {
      if (!t.currency().equals(currency)) {
        throw ApiException.conflict(
            "mixed_currency", "Invoice cannot mix currencies: " + currency + " and " + t.currency());
      }
      total = total.add(t.total());
    }
    List<InvoiceLine> lines =
        txs.stream()
            .map(
                t ->
                    new InvoiceLine(
                        t.id(),
                        t.type(),
                        t.externalId(),
                        t.currency(),
                        t.occurredAt(),
                        t.total(),
                        List.copyOf(t.taxLines()),
                        List.copyOf(t.feeLines()),
                        t.metadata().deepCopy()))
            .toList();
    return new Invoice(
        id, currency, total.setScale(2), lines, taxRollup(txs), feeRollup(txs), createdAt, updatedAt);
  }

  /** Grouped by name and rate; rates compare numerically, so 0.07 equals 0.070. */
  private List<TaxLine> taxRollup(List<Transaction> txs) {
    Map<String, TaxLine> grouped = new LinkedHashMap<>();
    for (Transaction t : txs) {
      for (TaxLine l : t.taxLines()) {
        String rate = l.rate() == null ? "" : l.rate().stripTrailingZeros().toPlainString();
        grouped.merge(
            l.name() + "|" + rate,
            l,
            (a, b) -> new TaxLine(a.name(), a.rate(), a.amount().add(b.amount())));
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
}
