package com.synera.api.catalog;

import com.synera.api.auth.ApiProblem;
import org.springframework.http.HttpStatus;

import java.math.BigInteger;

public final class PricingMath {
    private PricingMath() {
    }

    public static long multiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException exception) {
            throw new ApiProblem("PRICE_OVERFLOW", "Giá trị tính toán vượt giới hạn cho phép.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    public static long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new ApiProblem("PRICE_OVERFLOW", "Giá trị tính toán vượt giới hạn cho phép.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    public static long percentageDiscount(long amountVnd, int basisPoints) {
        return BigInteger.valueOf(amountVnd).multiply(BigInteger.valueOf(basisPoints))
                .divide(BigInteger.valueOf(10_000)).longValueExact();
    }

    public static long giftQuantity(long quantityInBaseUnits, long thresholdInBaseUnits, long giftQty) {
        if (thresholdInBaseUnits <= 0 || giftQty <= 0) {
            throw new ApiProblem("INVALID_PROMOTION_RULE", "Ngưỡng và số lượng hàng tặng phải lớn hơn 0.",
                    HttpStatus.BAD_REQUEST);
        }
        return multiply(quantityInBaseUnits / thresholdInBaseUnits, giftQty);
    }

    public static long fixedVoucherDiscount(long amountVnd, long payableVnd) {
        return Math.min(amountVnd, payableVnd);
    }
}
