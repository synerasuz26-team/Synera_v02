package com.synera.api.catalog;

import com.synera.api.auth.ApiProblem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PricingMathTest {
    @Test
    void discountsUseIntegerVndAndFloorFractionalDong() {
        assertEquals(2_880, PricingMath.percentageDiscount(96_000, 300));
        assertEquals(1, PricingMath.percentageDiscount(3, 5_000));
    }

    @Test
    void giftsRepeatOnlyForCompleteThresholdMultiples() {
        assertEquals(2, PricingMath.giftQuantity(480, 240, 1));
        assertEquals(1, PricingMath.giftQuantity(479, 240, 1));
    }

    @Test
    void fixedVoucherDiscountCannotExceedPayableAmount() {
        assertEquals(500, PricingMath.fixedVoucherDiscount(700, 500));
    }

    @Test
    void quantityOverflowReturnsAnExplicitApiProblem() {
        assertThrows(ApiProblem.class, () -> PricingMath.multiply(Long.MAX_VALUE, 2));
    }
}
