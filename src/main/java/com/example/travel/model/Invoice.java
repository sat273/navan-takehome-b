package com.example.travel.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record Invoice(
    String id,
    String currency,
    BigDecimal total,
    List<InvoiceLine> lines,
    List<TaxLine> taxRollup,
    List<FeeLine> feeRollup,
    Instant createdAt,
    Instant updatedAt) {}
