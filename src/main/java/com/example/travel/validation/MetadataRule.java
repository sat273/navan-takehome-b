package com.example.travel.validation;

import com.fasterxml.jackson.databind.JsonNode;

/** Validates metadata for one type. A new type is a new rule bean. */
public interface MetadataRule {
  String type();

  MetadataSummary validate(JsonNode metadata);
}
