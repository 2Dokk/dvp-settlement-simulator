package com.dvp.settlement.service;

import java.math.BigDecimal;

/**
 * Mutable running total for one {@link NetKey} while folding a batch's trades. Positive means
 * "net buyer" (receives securities, pays cash); negative means "net seller".
 */
final class NetAccumulator {
    long quantity = 0;
    BigDecimal amount = BigDecimal.ZERO;

    void addAsBuyer(long tradeQuantity, BigDecimal tradeAmount) {
        quantity += tradeQuantity;
        amount = amount.add(tradeAmount);
    }

    void addAsSeller(long tradeQuantity, BigDecimal tradeAmount) {
        quantity -= tradeQuantity;
        amount = amount.subtract(tradeAmount);
    }
}
