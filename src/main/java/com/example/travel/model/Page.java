package com.example.travel.model;

import java.util.List;

/** hasMore comes from fetching limit+1 rows, so there is no COUNT(*). */
public record Page<T>(List<T> items, int limit, int offset, boolean hasMore) {}
