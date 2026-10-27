package com.example.travel.api;

final class Paging {
  static final int MAX_LIMIT = 100;

  private Paging() {}

  /** Oversized limits are rejected rather than silently capped so clients notice. */
  static void check(int limit, int offset) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw ApiException.badRequest("limit must be between 1 and " + MAX_LIMIT);
    }
    if (offset < 0) {
      throw ApiException.badRequest("offset must be >= 0");
    }
  }
}
