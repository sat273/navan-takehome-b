package com.example.travel.validation;

import static com.example.travel.validation.JsonFields.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
class RailMetadataRule implements MetadataRule {
  @Override
  public String type() {
    return "rail";
  }

  @Override
  public MetadataSummary validate(JsonNode m) {
    JsonNode legs = nonEmptyArray(m, "legs", "rail metadata.legs");
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
}
