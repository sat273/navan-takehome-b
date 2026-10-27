package com.example.travel.model;

import java.util.List;

/** Offset page; hasMore is derived by fetching limit+1 rows so no COUNT(*) is needed. */
public record Page<T>(List<T> items, int limit, int offset, boolean hasMore) {}
