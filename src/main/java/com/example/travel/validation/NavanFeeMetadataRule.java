package com.example.travel.validation;

import static com.example.travel.validation.JsonFields.*;

import com.example.travel.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class NavanFeeMetadataRule implements MetadataRule {
  private static final Set<String> FEE_KINDS = Set.of("trip_fee", "agent_call_fee");

  @Override
  public String type() {
    return "navan_fee";
  }

  @Override
  public MetadataSummary validate(JsonNode m) {
    String kind = text(m, "fee_kind");
    if (!FEE_KINDS.contains(kind)) {
      throw ApiException.badRequest(
          "Unknown navan_fee fee_kind '" + kind + "'; expected trip_fee or agent_call_fee");
    }
    return new MetadataSummary(null, null, null, kind);
  }
}
