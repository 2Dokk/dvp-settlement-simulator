package com.dvp.settlement.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Not a correctness test — a throwaway demo that runs the exact same NetKey/NetAccumulator
 * folding BatchNettingPlanner uses, against one day's worth of trades in a single instrument, and
 * prints how many settlement instructions that collapses down to. Answers "how many trades did
 * netting turn into how many instructions" with a real number instead of a guess.
 */
class NettingReductionDemoTest {

    record DemoTrade(String buyer, String seller, long quantity, BigDecimal amount) {
    }

    @Test
    void oneTradingDayAcrossFiveParticipants() {
        // 18 trades in one instrument among 5 participants over one day.
        java.util.List<DemoTrade> trades = java.util.List.of(
                new DemoTrade("A", "B", 50, bd(500)),
                new DemoTrade("B", "C", 30, bd(300)),
                new DemoTrade("C", "D", 20, bd(200)),
                new DemoTrade("D", "E", 40, bd(400)),
                new DemoTrade("E", "A", 25, bd(250)),
                new DemoTrade("A", "C", 15, bd(150)),
                new DemoTrade("B", "D", 35, bd(350)),
                new DemoTrade("C", "E", 10, bd(100)),
                new DemoTrade("D", "A", 45, bd(450)),
                new DemoTrade("E", "B", 20, bd(200)),
                new DemoTrade("A", "D", 30, bd(300)),
                new DemoTrade("B", "E", 15, bd(150)),
                new DemoTrade("C", "A", 25, bd(250)),
                new DemoTrade("D", "B", 10, bd(100)),
                new DemoTrade("E", "C", 40, bd(400)),
                new DemoTrade("A", "E", 20, bd(200)),
                new DemoTrade("B", "A", 15, bd(150)),
                new DemoTrade("C", "B", 5, bd(50))
        );

        Map<String, NetAccumulator> net = new HashMap<>();
        for (DemoTrade t : trades) {
            net.computeIfAbsent(t.buyer(), k -> new NetAccumulator()).addAsBuyer(t.quantity(), t.amount());
            net.computeIfAbsent(t.seller(), k -> new NetAccumulator()).addAsSeller(t.quantity(), t.amount());
        }

        long instructionCount = net.values().stream().filter(a -> a.quantity != 0).count();

        System.out.println("=== netting reduction demo ===");
        System.out.println("raw trades:            " + trades.size());
        System.out.println("participants involved:  " + net.size());
        net.forEach((participant, acc) ->
                System.out.println("  " + participant + " net = " + acc.quantity + " qty / " + acc.amount + " KRW"));
        System.out.println("settlement instructions: " + instructionCount);
        System.out.println("reduction: " + trades.size() + " trades -> " + instructionCount + " instructions ("
                + Math.round((1 - (double) instructionCount / trades.size()) * 100) + "% fewer movements)");

        // Global conservation: every trade contributes +qty to a buyer and -qty to a seller.
        long totalQuantity = net.values().stream().mapToLong(a -> a.quantity).sum();
        BigDecimal totalAmount = net.values().stream().map(a -> a.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalQuantity).isZero();
        assertThat(totalAmount).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(instructionCount).isLessThan(trades.size());
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v).setScale(2);
    }
}
