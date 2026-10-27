package com.example.travel.validation;

import static com.example.travel.validation.JsonFields.*;

import com.example.travel.api.ApiException;
import com.example.travel.model.FeeLine;
import com.example.travel.model.TaxLine;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Stateless, so safe to share across threads. */
@Component
public class TransactionValidator {
  private static final Set<String> PATCHABLE =
      Set.of("total", "tax_lines", "fee_lines", "metadata", "occurred_at");

  private final Map<String, MetadataRule> rules;

  public TransactionValidator(List<MetadataRule> rules) {
    this.rules = rules.stream().collect(Collectors.toUnmodifiableMap(MetadataRule::type, Function.identity()));
  }

  public TransactionInput parseEnvelope(JsonNode body) {
    requireObject(body, "request body");
    String type = text(body, "type");
    if (!rules.containsKey(type)) {
      throw ApiException.badRequest(
          "Unknown type '" + type + "'; expected one of " + rules.keySet().stream().sorted().toList());
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

  public MetadataSummary validateMetadata(String type, JsonNode metadata) {
    requireObject(metadata, "metadata");
    MetadataRule rule = rules.get(type);
    if (rule == null) {
      throw ApiException.badRequest("Unknown type '" + type + "'");
    }
    return rule.validate(metadata);
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
}
