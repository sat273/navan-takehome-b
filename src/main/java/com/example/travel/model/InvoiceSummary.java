package com.example.travel.model;

import java.math.BigDecimal;
import java.time.Instant;

public record InvoiceSummary(
    String id, String currency, BigDecimal total, int lineCount, Instant createdAt) {}
