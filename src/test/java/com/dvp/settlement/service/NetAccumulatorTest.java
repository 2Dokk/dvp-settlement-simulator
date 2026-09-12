package com.dvp.settlement.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class NetAccumulatorTest {

    @Test
    void buyerSideAddsPositiveQuantityAndAmount() {
        NetAccumulator acc = new NetAccumulator();
        acc.addAsBuyer(10, new BigDecimal("1000.00"));

        assertThat(acc.quantity).isEqualTo(10);
        assertThat(acc.amount).isEqualByComparingTo("1000.00");
    }

    @Test
    void sellerSideSubtractsQuantityAndAmount() {
        NetAccumulator acc = new NetAccumulator();
        acc.addAsSeller(10, new BigDecimal("1000.00"));

        assertThat(acc.quantity).isEqualTo(-10);
        assertThat(acc.amount).isEqualByComparingTo("-1000.00");
    }

    @Test
    void multipleTradesInBothDirectionsNetDownToTheResidual() {
        // Participant buys 10 @ 100, then sells 6 @ 100 of the same instrument the same day.
        NetAccumulator acc = new NetAccumulator();
        acc.addAsBuyer(10, new BigDecimal("1000.00"));
        acc.addAsSeller(6, new BigDecimal("600.00"));

        assertThat(acc.quantity).isEqualTo(4);
        assertThat(acc.amount).isEqualByComparingTo("400.00");
    }

    @Test
    void perfectlyOffsettingTradesNetToZero() {
        NetAccumulator acc = new NetAccumulator();
        acc.addAsBuyer(5, new BigDecimal("500.00"));
        acc.addAsSeller(5, new BigDecimal("500.00"));

        assertThat(acc.quantity).isZero();
        assertThat(acc.amount).isEqualByComparingTo("0.00");
    }
}
