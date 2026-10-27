package com.example.travel.validation;

import com.example.travel.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** JsonNode readers that throw a 400 on bad input. */
final class JsonFields {
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");

  private JsonFields() {}

  static void requireObject(JsonNode n, String what) {
    if (n == null || !n.isObject()) {
      throw ApiException.badRequest(what + " must be a JSON object");
    }
  }

  static JsonNode nonEmptyArray(JsonNode parent, String field, String what) {
    JsonNode n = parent.get(field);
    if (n == null || !n.isArray() || n.isEmpty()) {
      throw ApiException.badRequest(what + " must be a non-empty array");
    }
    return n;
  }

  static JsonNode array(JsonNode n, String field) {
    if (n == null || !n.isArray()) {
      throw ApiException.badRequest(field + " is required and must be an array");
    }
    return n;
  }

  static String text(JsonNode parent, String field) {
    JsonNode n = parent.get(field);
    if (n == null || !n.isTextual() || n.asText().isBlank()) {
      throw ApiException.badRequest(field + " is required and must be a non-blank string");
    }
    return n.asText();
  }

  static Instant instant(JsonNode parent, String field) {
    try {
      return Instant.parse(text(parent, field));
    } catch (DateTimeParseException e) {
      throw ApiException.badRequest(field + " must be an ISO-8601 instant, e.g. 2026-03-11T06:40:00Z");
    }
  }

  static LocalDate date(JsonNode parent, String field) {
    try {
      return LocalDate.parse(text(parent, field));
    } catch (DateTimeParseException e) {
      throw ApiException.badRequest(field + " must be an ISO date, e.g. 2026-03-11");
    }
  }

  static BigDecimal number(JsonNode n, String field) {
    if (n == null || !n.isNumber()) {
      throw ApiException.badRequest(field + " is required and must be a number");
    }
    return n.decimalValue();
  }

  /** More than 2 decimals is rejected, never rounded. */
  static BigDecimal amount(JsonNode parent, String field) {
    BigDecimal v = number(parent.get(field), field);
    if (v.stripTrailingZeros().scale() > 2) {
      throw ApiException.badRequest(field + " must have at most 2 decimal places");
    }
    if (v.abs().compareTo(MAX_AMOUNT) >= 0) {
      throw ApiException.badRequest(field + " is out of range");
    }
    return v.setScale(2);
  }
}
