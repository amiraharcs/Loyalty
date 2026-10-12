package com.amiraharcs.loyalty.loyalty.model;

import java.math.BigInteger;

/** Exact integer bounds shared by future mutation validation and safe API projections. */
public final class PointValues {
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final BigInteger MAX = BigInteger.valueOf(MAX_SAFE_INTEGER);
    private PointValues() { }

    public static long safeInteger(BigInteger value) {
        if (value == null || value.abs().compareTo(MAX) > 0) {
            throw new IllegalStateException("Point value is outside the supported integer range");
        }
        return value.longValueExact();
    }

    public static long checkedTotal(long balance, long change) {
        return safeInteger(BigInteger.valueOf(balance).add(BigInteger.valueOf(change)));
    }

    public static long amount(long value) {
        if (value == 0 || value > MAX_SAFE_INTEGER || value < -MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("Point amount must be a nonzero supported integer");
        }
        return value;
    }
}
