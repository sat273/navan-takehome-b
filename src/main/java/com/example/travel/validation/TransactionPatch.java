package com.example.travel.validation;

import com.example.travel.model.FeeLine;
import com.example.travel.model.TaxLine;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Null means the field was not sent. */
public record TransactionPatch(
    Instant occurredAt,
    BigDecimal total,
    List<TaxLine> taxLines,
    List<FeeLine> feeLines,
    JsonNode metadata) {}
