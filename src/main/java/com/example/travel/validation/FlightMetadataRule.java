package com.example.travel.validation;

import static com.example.travel.validation.JsonFields.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
class FlightMetadataRule implements MetadataRule {
  @Override
  public String type() {
    return "flight";
  }

  @Override
  public MetadataSummary validate(JsonNode m) {
    String origin = text(m, "origin");
    String destination = text(m, "destination");
    amount(m, "fare");
    for (JsonNode c : nonEmptyArray(m, "coupons", "flight metadata.coupons")) {
      requireObject(c, "coupon");
      text(c, "flight_number");
      text(c, "from");
      text(c, "to");
      instant(c, "departure");
      instant(c, "arrival");
    }
    return new MetadataSummary(origin, destination, null, null);
  }
}
