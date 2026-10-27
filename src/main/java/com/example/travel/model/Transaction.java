package com.example.travel.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record Transaction(
    String id,
    String type,
    String externalId,
    String currency,
    Instant occurredAt,
    BigDecimal total,
    List<TaxLine> taxLines,
    List<FeeLine> feeLines,
    JsonNode metadata,
    Instant createdAt,
    Instant updatedAt) {}
