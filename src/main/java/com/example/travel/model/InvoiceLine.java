package com.example.travel.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Copy of a transaction as it was at invoice time. */
public record InvoiceLine(
    String transactionId,
    String type,
    String externalId,
    String currency,
    Instant occurredAt,
    BigDecimal total,
    List<TaxLine> taxLines,
    List<FeeLine> feeLines,
    JsonNode metadata) {}
