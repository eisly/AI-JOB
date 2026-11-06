package com.aijob.backend.job;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CompanySizesTest {

    @Test
    void boundariesDoNotOverlapOrLeaveGaps() {
        assertEquals(CompanySizes.SIZE_0_20, CompanySizes.forHeadcount(0));
        assertEquals(CompanySizes.SIZE_0_20, CompanySizes.forHeadcount(20));
        assertEquals(CompanySizes.SIZE_21_99, CompanySizes.forHeadcount(21));
        assertEquals(CompanySizes.SIZE_21_99, CompanySizes.forHeadcount(99));
        assertEquals(CompanySizes.SIZE_100_499, CompanySizes.forHeadcount(100));
        assertEquals(CompanySizes.SIZE_100_499, CompanySizes.forHeadcount(499));
        assertEquals(CompanySizes.SIZE_500_999, CompanySizes.forHeadcount(500));
        assertEquals(CompanySizes.SIZE_500_999, CompanySizes.forHeadcount(999));
        assertEquals(CompanySizes.SIZE_1000_9999, CompanySizes.forHeadcount(1000));
        assertEquals(CompanySizes.SIZE_1000_9999, CompanySizes.forHeadcount(9999));
        assertEquals(CompanySizes.SIZE_10000_PLUS, CompanySizes.forHeadcount(10000));
        assertEquals(CompanySizes.SIZE_UNKNOWN, CompanySizes.forHeadcount(null));
    }

    @Test
    void parsesOnlyExplicitRangesAndKeepsUnknownUnknown() {
        assertEquals(CompanySizes.SIZE_0_20, CompanySizes.parse("0-20人"));
        assertEquals(CompanySizes.SIZE_21_99, CompanySizes.parse("21-99人"));
        assertEquals(CompanySizes.SIZE_100_499, CompanySizes.parse("100-499人"));
        assertEquals(CompanySizes.SIZE_500_999, CompanySizes.parse("500-999人"));
        assertEquals(CompanySizes.SIZE_1000_9999, CompanySizes.parse("1000-9999人"));
        assertEquals(CompanySizes.SIZE_10000_PLUS, CompanySizes.parse("10000人以上"));
        assertEquals(CompanySizes.SIZE_0_20, CompanySizes.parse("20人以下"));
        assertEquals(CompanySizes.SIZE_UNKNOWN, CompanySizes.parse("规模未知"));
        assertEquals(CompanySizes.SIZE_UNKNOWN, CompanySizes.parse("保密"));
        assertEquals(CompanySizes.SIZE_UNKNOWN, CompanySizes.parse(""));
    }

    @Test
    void normalizesExternalValues() {
        assertEquals(CompanySizes.SIZE_500_999, CompanySizes.normalize("size_500_999"));
        assertEquals(CompanySizes.SIZE_UNKNOWN, CompanySizes.normalize("500-999"));
        assertEquals(CompanySizes.SIZE_UNKNOWN, CompanySizes.normalize(null));
    }
}