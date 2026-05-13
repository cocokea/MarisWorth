package com.maris7.worth.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class NumberFormatUtil {
    private static final String[] SUFFIXES = {"", "K", "M", "B", "T", "Q"};

    private NumberFormatUtil() {
    }

    public static String format(double value) {
        if (Math.abs(value) < 1000D) {
            return strip(BigDecimal.valueOf(value));
        }
        double current = value;
        int suffixIndex = 0;
        while (Math.abs(current) >= 1000D && suffixIndex < SUFFIXES.length - 1) {
            current /= 1000D;
            suffixIndex++;
        }
        return strip(BigDecimal.valueOf(current)) + SUFFIXES[suffixIndex];
    }

    private static String strip(BigDecimal value) {
        BigDecimal rounded = value.setScale(value.scale() > 0 ? 1 : 0, RoundingMode.DOWN).stripTrailingZeros();
        return rounded.toPlainString();
    }
}
