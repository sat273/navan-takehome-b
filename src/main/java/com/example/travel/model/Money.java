package com.example.travel.model;

import java.math.BigDecimal;

/** Stored as integer cents, exposed as BigDecimal with scale 2. */
public final class Money {
  public static final int SCALE = 2;

  private Money() {}

  public static long toMinor(BigDecimal amount) {
    return amount.setScale(SCALE).unscaledValue().longValueExact();
  }

  public static BigDecimal fromMinor(long minor) {
    return BigDecimal.valueOf(minor, SCALE);
  }
}
