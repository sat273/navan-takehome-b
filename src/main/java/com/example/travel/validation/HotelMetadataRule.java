package com.example.travel.validation;

import static com.example.travel.validation.JsonFields.*;

import com.example.travel.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
class HotelMetadataRule implements MetadataRule {
  @Override
  public String type() {
    return "hotel";
  }

  @Override
  public MetadataSummary validate(JsonNode m) {
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
}
