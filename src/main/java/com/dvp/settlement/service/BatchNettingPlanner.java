package com.dvp.settlement.service;

import java.math.BigDecimal;
import java.util.*;

/**
 * Decides, before any balance moves, which of a batch's trades can settle together.
 *
 * <p>Nets the trades per (participant, instrument), checks every participant's obligations
 * against a locked balance snapshot, and if anyone is short, excludes that participant's trades
 * and re-nets — excluding someone changes their counterparties' positions, which can resolve or
 * create other shortfalls. Only one participant is excluded per round (lowest id first), so a
 * counterparty whose shortfall was caused solely by the excluded participant gets a chance to
 * settle instead of being dropped in the same round. The loop ends when every remaining position
 * is covered, and terminates because the trade set strictly shrinks each round.
 *
 * <p>This greedy policy is deterministic but not optimal: maximizing settled value is an
 * optimization problem real CSDs solve with dedicated algorithms.
 *
 * <p>Pure function — no database, no Spring — so the exclusion logic is unit-testable directly.
 */
public final class BatchNettingPlanner {

    private BatchNettingPlanner() {
    }

    public record TradeLeg(long tradeId, long buyerId, long sellerId, long instrumentId,
                           long quantity, BigDecimal amount) {
    }

    public record BalanceSnapshot(Map<Long, BigDecimal> cashByParticipant, Map<NetKey, Long> holdings) {
        BigDecimal cashOf(long participantId) {
            return cashByParticipant.getOrDefault(participantId, BigDecimal.ZERO);
        }

        long holdingOf(long participantId, long instrumentId) {
            return holdings.getOrDefault(new NetKey(participantId, instrumentId), 0L);
        }
    }

    public record Plan(List<TradeLeg> settleable,
                       Map<Long, String> failedTradeReasons,
                       Map<NetKey, NetAccumulator> netPositions) {
    }

    public static Plan plan(List<TradeLeg> trades, BalanceSnapshot snapshot) {
        // Insertion order = exclusion order, so a failed trade is attributed to whichever of its
        // two parties was excluded first (the root cause, not a knock-on exclusion).
        Map<Long, String> exclusionReasons = new LinkedHashMap<>();

        while (true) {
            List<TradeLeg> active = trades.stream()
                    .filter(t -> !exclusionReasons.containsKey(t.buyerId()) && !exclusionReasons.containsKey(t.sellerId()))
                    .toList();
            Map<NetKey, NetAccumulator> net = net(active);

            Optional<Map.Entry<Long, String>> shortfall = firstShortfall(net, snapshot);
            if (shortfall.isEmpty()) {
                return new Plan(active, failureReasons(trades, exclusionReasons), net);
            }
            exclusionReasons.put(shortfall.get().getKey(), shortfall.get().getValue());
        }
    }

    static Map<NetKey, NetAccumulator> net(List<TradeLeg> trades) {
        Map<NetKey, NetAccumulator> net = new TreeMap<>(Comparator
                .comparing(NetKey::participantId)
                .thenComparing(NetKey::instrumentId));
        for (TradeLeg t : trades) {
            net.computeIfAbsent(new NetKey(t.buyerId(), t.instrumentId()), k -> new NetAccumulator())
                    .addAsBuyer(t.quantity(), t.amount());
            net.computeIfAbsent(new NetKey(t.sellerId(), t.instrumentId()), k -> new NetAccumulator())
                    .addAsSeller(t.quantity(), t.amount());
        }
        return net;
    }

    private static Optional<Map.Entry<Long, String>> firstShortfall(Map<NetKey, NetAccumulator> net,
                                                                   BalanceSnapshot snapshot) {
        // Cash is checked in aggregate across instruments: a participant paying for one instrument
        // while being paid for another only needs to cover the difference.
        Map<Long, BigDecimal> cashOwed = new TreeMap<>();
        Map<Long, String> securitiesShortfall = new TreeMap<>();
        for (Map.Entry<NetKey, NetAccumulator> e : net.entrySet()) {
            long participant = e.getKey().participantId();
            long instrument = e.getKey().instrumentId();
            NetAccumulator acc = e.getValue();
            cashOwed.merge(participant, acc.amount, BigDecimal::add);

            long toDeliver = -acc.quantity;
            long held = snapshot.holdingOf(participant, instrument);
            if (toDeliver > 0 && held < toDeliver && !securitiesShortfall.containsKey(participant)) {
                securitiesShortfall.put(participant, "insufficient securities in instrument %d: holding=%d, required=%d"
                        .formatted(instrument, held, toDeliver));
            }
        }

        for (Map.Entry<Long, BigDecimal> e : cashOwed.entrySet()) {
            long participant = e.getKey();
            if (securitiesShortfall.containsKey(participant)) {
                return Optional.of(Map.entry(participant, securitiesShortfall.get(participant)));
            }
            BigDecimal owed = e.getValue();
            BigDecimal balance = snapshot.cashOf(participant);
            if (owed.signum() > 0 && balance.compareTo(owed) < 0) {
                return Optional.of(Map.entry(participant, "insufficient cash: balance=%s, required=%s"
                        .formatted(balance, owed)));
            }
        }
        return Optional.empty();
    }

    private static Map<Long, String> failureReasons(List<TradeLeg> trades, Map<Long, String> exclusionReasons) {
        Map<Long, String> reasons = new LinkedHashMap<>();
        for (TradeLeg t : trades) {
            exclusionReasons.entrySet().stream()
                    .filter(e -> e.getKey() == t.buyerId() || e.getKey() == t.sellerId())
                    .findFirst()
                    .ifPresent(e -> reasons.put(t.tradeId(),
                            "participant %d excluded from batch: %s".formatted(e.getKey(), e.getValue())));
        }
        return reasons;
    }
}
