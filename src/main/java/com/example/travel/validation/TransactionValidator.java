package com.example.travel.validation;

import com.example.travel.api.ApiException;
import com.example.travel.model.FeeLine;
import com.example.travel.model.TaxLine;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class TransactionValidator {
  private static final Set<String> PATCHABLE =
      Set.of("total", "tax_lines", "fee_lines", "metadata", "occurred_at");

  public TransactionInput parseEnvelope(JsonNode body) {
    requireObject(body, "request body");
    String type = text(body, "type");
    if (!TransactionTypes.ALL.contains(type)) {
      throw ApiException.badRequest("Unknown type '" + type + "'; expected one of flight, hotel, rail, navan_fee");
    }
    String currency = text(body, "currency");
    if (!currency.matches("[A-Z]{3}")) {
      throw ApiException.badRequest("currency must be a 3-letter uppercase ISO code");
    }
    JsonNode metadata = body.get("metadata");
    requireObject(metadata, "metadata");
    if (metadata.isEmpty()) {
      throw ApiException.badRequest("metadata must not be empty");
    }
    return new TransactionInput(
        type,
        text(body, "external_id"),
        currency,
        instant(body, "occurred_at"),
        amount(body, "total"),
        taxLines(body.get("tax_lines")),
        feeLines(body.get("fee_lines")),
        metadata);
  }

  public TransactionPatch parsePatch(JsonNode body) {
    requireObject(body, "request body");
    body.fieldNames()
        .forEachRemaining(
            f -> {
              if (!PATCHABLE.contains(f)) {
                throw ApiException.badRequest("Field '" + f + "' cannot be patched");
              }
            });
    if (body.isEmpty()) {
      throw ApiException.badRequest("Patch body must contain at least one field");
    }
    JsonNode metadata = body.get("metadata");
    if (metadata != null) {
      requireObject(metadata, "metadata");
    }
    return new TransactionPatch(
        body.has("occurred_at") ? instant(body, "occurred_at") : null,
        body.has("total") ? amount(body, "total") : null,
        body.has("tax_lines") ? taxLines(body.get("tax_lines")) : null,
        body.has("fee_lines") ? feeLines(body.get("fee_lines")) : null,
        metadata);
  }

  public MetadataSummary validateMetadata(String type, JsonNode m) {
    requireObject(m, "metadata");
    return switch (type) {
      case "flight" -> flight(m);
      case "hotel" -> hotel(m);
      case "rail" -> rail(m);
      case "navan_fee" -> navanFee(m);
      default -> throw ApiException.badRequest("Unknown type '" + type + "'");
    };
  }

  private MetadataSummary flight(JsonNode m) {
    String origin = text(m, "origin");
    String destination = text(m, "destination");
    amount(m, "fare");
    JsonNode coupons = m.get("coupons");
    if (coupons == null || !coupons.isArray() || coupons.isEmpty()) {
      throw ApiException.badRequest("flight metadata.coupons must be a non-empty array");
    }
    for (JsonNode c : coupons) {
      requireObject(c, "coupon");
      text(c, "flight_number");
      text(c, "from");
      text(c, "to");
      instant(c, "departure");
      instant(c, "arrival");
    }
    return new MetadataSummary(origin, destination, null, null);
  }

  private MetadataSummary hotel(JsonNode m) {
    String name = text(m, "name");
    JsonNode address = m.get("address");
    requireObject(address, "metadata.address");
    text(address, "street");
    text(address, "city");
    text(address, "country");
    date(m, "check_in");
    date(m, "check_out");
    JsonNode nights = m.get("nights");
    if (nights == null || !nights.isIntegralNumber() || nights.asInt() < 1) {
      throw ApiException.badRequest("hotel metadata.nights must be a positive integer");
    }
    amount(m, "room_rate_per_night");
    return new MetadataSummary(null, null, name, null);
  }

  private MetadataSummary rail(JsonNode m) {
    JsonNode legs = m.get("legs");
    if (legs == null || !legs.isArray() || legs.isEmpty()) {
      throw ApiException.badRequest("rail metadata.legs must be a non-empty array");
    }
    for (JsonNode leg : legs) {
      requireObject(leg, "leg");
      text(leg, "origin");
      text(leg, "destination");
      instant(leg, "departure");
      instant(leg, "arrival");
    }
    return new MetadataSummary(
        legs.get(0).get("origin").asText(),
        legs.get(legs.size() - 1).get("destination").asText(),
        null,
        null);
  }

  private MetadataSummary navanFee(JsonNode m) {
    String kind = text(m, "fee_kind");
    if (!TransactionTypes.NAVAN_FEE_KINDS.contains(kind)) {
      throw ApiException.badRequest(
          "Unknown navan_fee fee_kind '" + kind + "'; expected trip_fee or agent_call_fee");
    }
    return new MetadataSummary(null, null, null, kind);
  }

  private List<TaxLine> taxLines(JsonNode node) {
    List<TaxLine> out = new ArrayList<>();
    for (JsonNode n : array(node, "tax_lines")) {
      requireObject(n, "tax_lines entry");
      BigDecimal rate = null;
      if (n.hasNonNull("rate")) {
        rate = number(n.get("rate"), "rate");
        if (rate.signum() < 0) {
          throw ApiException.badRequest("tax_lines rate must not be negative");
        }
      }
      out.add(new TaxLine(text(n, "name"), rate, amount(n, "amount")));
    }
    return out;
  }

  private List<FeeLine> feeLines(JsonNode node) {
    List<FeeLine> out = new ArrayList<>();
    for (JsonNode n : array(node, "fee_lines")) {
      requireObject(n, "fee_lines entry");
      out.add(new FeeLine(text(n, "name"), amount(n, "amount")));
    }
    return out;
  }

  private JsonNode array(JsonNode node, String field) {
    if (node == null || !node.isArray()) {
      throw ApiException.badRequest(field + " is required and must be an array");
    }
    return node;
  }

  private void requireObject(JsonNode n, String what) {
    if (n == null || !n.isObject()) {
      throw ApiException.badRequest(what + " must be a JSON object");
    }
  }

  private String text(JsonNode parent, String field) {
    JsonNode n = parent.get(field);
    if (n == null || !n.isTextual() || n.asText().isBlank()) {
      throw ApiException.badRequest(field + " is required and must be a non-blank string");
    }
    return n.asText();
  }

  private Instant instant(JsonNode parent, String field) {
    try {
      return Instant.parse(text(parent, field));
    } catch (DateTimeParseException e) {
      throw ApiException.badRequest(field + " must be an ISO-8601 instant, e.g. 2026-03-11T06:40:00Z");
    }
  }

  private LocalDate date(JsonNode parent, String field) {
    try {
      return LocalDate.parse(text(parent, field));
    } catch (DateTimeParseException e) {
      throw ApiException.badRequest(field + " must be an ISO date, e.g. 2026-03-11");
    }
  }

  private BigDecimal number(JsonNode n, String field) {
    if (n == null || !n.isNumber()) {
      throw ApiException.badRequest(field + " is required and must be a number");
    }
    return n.decimalValue();
  }

  /** Money must fit in 2 decimal places; extra precision is rejected rather than silently rounded. */
  private BigDecimal amount(JsonNode parent, String field) {
    BigDecimal v = number(parent.get(field), field);
    if (v.stripTrailingZeros().scale() > 2) {
      throw ApiException.badRequest(field + " must have at most 2 decimal places");
    }
    if (v.abs().compareTo(new BigDecimal("1000000000000")) >= 0) {
      throw ApiException.badRequest(field + " is out of range");
    }
    return v.setScale(2);
  }
}
