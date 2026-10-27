package com.example.travel.validation;

/** Type-specific values lifted out of metadata into indexed columns. */
public record MetadataSummary(String origin, String destination, String hotelName, String feeKind) {}
