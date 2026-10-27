package com.example.travel.model;

import java.math.BigDecimal;

public record TaxLine(String name, BigDecimal rate, BigDecimal amount) {}
