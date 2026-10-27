package com.example.travel.validation;

import com.example.travel.model.FeeLine;
import com.example.travel.model.TaxLine;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TransactionInput(
    String type,
    String externalId,
    String currency,
    Instant occurredAt,
    BigDecimal total,
    List<TaxLine> taxLines,
    List<FeeLine> feeLines,
    JsonNode metadata) {}
