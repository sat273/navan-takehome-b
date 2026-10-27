package com.example.travel.validation;

import java.util.Set;

public final class TransactionTypes {
  public static final Set<String> ALL = Set.of("flight", "hotel", "rail", "navan_fee");
  public static final Set<String> NAVAN_FEE_KINDS = Set.of("trip_fee", "agent_call_fee");

  private TransactionTypes() {}
}
