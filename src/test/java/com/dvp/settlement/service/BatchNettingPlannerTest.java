package com.dvp.settlement.service;

import com.dvp.settlement.service.BatchNettingPlanner.BalanceSnapshot;
import com.dvp.settlement.service.BatchNettingPlanner.Plan;
import com.dvp.settlement.service.BatchNettingPlanner.TradeLeg;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BatchNettingPlannerTest {

    private static final long X = 100;
    private static final long Y = 200;

    @Test
    void everythingSettlesWhenAllObligationsAreCovered() {
        List<TradeLeg> trades = List.of(
                trade(1, 1, 2, X, 100),
                trade(2, 2, 3, X, 40),
                trade(3, 3, 1, X, 20));
        BalanceSnapshot snapshot = snapshot()
                .cash(1, 10_000).cash(2, 10_000).cash(3, 10_000)
                .holding(1, X, 20).holding(2, X, 100).holding(3, X, 40)
                .build();

        Plan plan = BatchNettingPlanner.plan(trades, snapshot);

        assertThat(plan.settleable()).hasSize(3);
        assertThat(plan.failedTradeReasons()).isEmpty();
        assertNetsToZero(plan);
    }

    @Test
    void shortParticipantIsExcludedAndEveryoneElseStillSettles() {
        // Participant 1 can't pay for instrument X; participants 3 and 4 trade Y independently.
        List<TradeLeg> trades = List.of(
                trade(1, 1, 2, X, 50),
                trade(2, 3, 4, Y, 30));
        BalanceSnapshot snapshot = snapshot()
                .cash(1, 100).holding(2, X, 50)
                .cash(3, 1_000).holding(4, Y, 100)
                .build();

        Plan plan = BatchNettingPlanner.plan(trades, snapshot);

        assertThat(plan.settleable()).extracting(TradeLeg::tradeId).containsExactly(2L);
        assertThat(plan.failedTradeReasons()).containsOnlyKeys(1L);
        assertThat(plan.failedTradeReasons().get(1L)).contains("participant 1").contains("insufficient cash");
        assertNetsToZero(plan);
    }

    @Test
    void exclusionCascadesWhenRemovingOneParticipantBreaksAnother() {
        // 2 holds no X but nets to zero: sells 50 to 1, buys 50 from 3. 3 can't deliver, so once
        // 3 is out, 2 becomes a net deliverer with nothing to deliver and must be excluded too.
        List<TradeLeg> trades = List.of(
                trade(1, 1, 2, X, 50),
                trade(2, 2, 3, X, 50));
        BalanceSnapshot snapshot = snapshot()
                .cash(1, 10_000).cash(2, 10_000)
                .build();

        Plan plan = BatchNettingPlanner.plan(trades, snapshot);

        assertThat(plan.settleable()).isEmpty();
        assertThat(plan.failedTradeReasons()).containsOnlyKeys(1L, 2L);
        // Trade 2's root cause is 3 (excluded first), not the knock-on exclusion of 2.
        assertThat(plan.failedTradeReasons().get(2L)).contains("participant 3");
        assertThat(plan.failedTradeReasons().get(1L)).contains("participant 2");
        assertThat(plan.netPositions()).isEmpty();
    }

    @Test
    void priceDifferencesWithinADayAreStillCheckedOnTheCashLeg() {
        // 1 buys 10 X @1 (pays 10) and sells 5 X @100 (receives 500): nets +5 X but -490 cash,
        // i.e. receives both securities AND cash. 2 must pay 500 for its 5 units.
        List<TradeLeg> trades = List.of(
                new TradeLeg(1, 1, 3, X, 10, BigDecimal.valueOf(10)),
                new TradeLeg(2, 2, 1, X, 5, BigDecimal.valueOf(500)));
        BalanceSnapshot snapshot = snapshot()
                .holding(3, X, 10).cash(2, 499).cash(1, 10)
                .build();

        Plan plan = BatchNettingPlanner.plan(trades, snapshot);

        // 2 is 1 short on cash, so trade 2 drops out; trade 1 alone still settles (1 then owes
        // the full 10 for it, which it has).
        assertThat(plan.settleable()).extracting(TradeLeg::tradeId).containsExactly(1L);
        assertThat(plan.failedTradeReasons().get(2L)).contains("participant 2").contains("insufficient cash");
        assertNetsToZero(plan);
    }

    @Test
    void excludingOneParticipantAtATimeKeepsACounterpartyWhoseShortfallItCaused() {
        // 1 sells X to 2 but holds no X. 2 has no cash, so 2 also looks short in round one — but
        // only because of that trade. Excluding just 1 first lets 2's other trade (selling Y to
        // 3) settle; excluding both at once would have failed trade 2 for no reason.
        List<TradeLeg> trades = List.of(
                trade(1, 2, 1, X, 50),
                trade(2, 3, 2, Y, 20));
        BalanceSnapshot snapshot = snapshot()
                .holding(2, Y, 20)
                .cash(3, 1_000)
                .build();

        Plan plan = BatchNettingPlanner.plan(trades, snapshot);

        assertThat(plan.settleable()).extracting(TradeLeg::tradeId).containsExactly(2L);
        assertThat(plan.failedTradeReasons()).containsOnlyKeys(1L);
        assertThat(plan.failedTradeReasons().get(1L)).contains("participant 1").contains("insufficient securities");
    }

    @Test
    void cashIsCheckedInAggregateAcrossInstruments() {
        // 1 pays 500 for X but receives 500 for Y the same day, with zero cash on hand.
        List<TradeLeg> trades = List.of(
                trade(1, 1, 2, X, 50),
                trade(2, 3, 1, Y, 50));
        BalanceSnapshot snapshot = snapshot()
                .holding(2, X, 50).holding(1, Y, 50)
                .cash(3, 500)
                .build();

        Plan plan = BatchNettingPlanner.plan(trades, snapshot);

        assertThat(plan.settleable()).hasSize(2);
        assertThat(plan.failedTradeReasons()).isEmpty();
    }

    private static void assertNetsToZero(Plan plan) {
        Map<Long, Long> quantityByInstrument = new HashMap<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map.Entry<NetKey, NetAccumulator> e : plan.netPositions().entrySet()) {
            quantityByInstrument.merge(e.getKey().instrumentId(), e.getValue().quantity, Long::sum);
            totalAmount = totalAmount.add(e.getValue().amount);
        }
        assertThat(quantityByInstrument.values()).allMatch(q -> q == 0);
        assertThat(totalAmount).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static TradeLeg trade(long tradeId, long buyer, long seller, long instrument, long quantity) {
        return new TradeLeg(tradeId, buyer, seller, instrument, quantity, BigDecimal.valueOf(quantity * 10));
    }

    private static SnapshotBuilder snapshot() {
        return new SnapshotBuilder();
    }

    private static final class SnapshotBuilder {
        private final Map<Long, BigDecimal> cash = new HashMap<>();
        private final Map<NetKey, Long> holdings = new HashMap<>();

        SnapshotBuilder cash(long participant, long amount) {
            cash.put(participant, BigDecimal.valueOf(amount));
            return this;
        }

        SnapshotBuilder holding(long participant, long instrument, long quantity) {
            holdings.put(new NetKey(participant, instrument), quantity);
            return this;
        }

        BalanceSnapshot build() {
            return new BalanceSnapshot(cash, holdings);
        }
    }
}
