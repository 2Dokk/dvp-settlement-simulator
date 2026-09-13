package com.dvp.settlement.service;

import com.dvp.settlement.domain.*;
import com.dvp.settlement.repository.*;
import com.dvp.settlement.service.BatchNettingPlanner.BalanceSnapshot;
import com.dvp.settlement.service.BatchNettingPlanner.Plan;
import com.dvp.settlement.service.BatchNettingPlanner.TradeLeg;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Settles one settlement date's trades as a single all-or-nothing database transaction.
 *
 * <p>Earlier versions executed each participant's net instruction against the CCP in its own
 * transaction; if one participant failed after others had settled, the CCP was left with a
 * permanent residual. Here, nothing moves until {@link BatchNettingPlanner} has found a set of
 * trades whose every obligation is covered by balances read under lock. That set's net
 * positions sum to zero per instrument and in cash, so participants' balances change by exactly
 * their net positions and the CCP's balances never change at all — the CCP appears only as the
 * ledger counterparty. A residual is structurally impossible rather than merely unlikely.
 *
 * <p>Lock order (shared with {@link SettlementService}): trades (via the claiming UPDATE), then
 * CASH accounts by ascending id, then SECURITIES holdings by ascending id.
 *
 * <p>Trade-off: the whole batch holds its account locks for one transaction, which serializes
 * batches that share participants. That is acceptable for end-of-day style batches.
 */
@Service
@RequiredArgsConstructor
public class BatchSettlementService {

    private final SettlementBatchRepository batchRepository;
    private final TradeRepository tradeRepository;
    private final NetPositionRepository netPositionRepository;
    private final CashAccountRepository cashAccountRepository;
    private final SecurityHoldingRepository securityHoldingRepository;
    private final AccountService accountService;
    private final LedgerService ledgerService;

    @Transactional
    public SettlementBatch runBatch(LocalDate settlementDate) {
        Long batchId = batchRepository.save(new SettlementBatch(settlementDate)).getId();
        tradeRepository.claimPendingForBatch(settlementDate, batchId);
        // The claiming UPDATE clears the persistence context; reload managed instances.
        SettlementBatch batch = batchRepository.findById(batchId).orElseThrow();
        List<Trade> trades = tradeRepository.findByBatchId(batchId);

        if (!trades.isEmpty()) {
            settle(batchId, trades);
        }

        batch.setStatus(BatchStatus.COMPLETED);
        batch.setCompletedAt(Instant.now());
        return batch;
    }

    private void settle(Long batchId, List<Trade> trades) {
        Long ccpId = accountService.ccpParticipantId();
        Long ccpCashId = accountService.getOrCreateCashAccount(ccpId, AccountService.DEFAULT_CURRENCY).getId();

        Map<Long, Long> cashIdByParticipant = new TreeMap<>();
        Map<NetKey, Long> holdingIdByKey = new HashMap<>();
        Map<Long, Long> ccpHoldingIdByInstrument = new HashMap<>();
        for (Trade t : trades) {
            for (Long participant : List.of(t.getBuyerId(), t.getSellerId())) {
                cashIdByParticipant.computeIfAbsent(participant, p ->
                        accountService.getOrCreateCashAccount(p, AccountService.DEFAULT_CURRENCY).getId());
                holdingIdByKey.computeIfAbsent(new NetKey(participant, t.getInstrumentId()), k ->
                        accountService.getOrCreateSecurityHolding(k.participantId(), k.instrumentId()).getId());
            }
            ccpHoldingIdByInstrument.computeIfAbsent(t.getInstrumentId(), i ->
                    accountService.getOrCreateSecurityHolding(ccpId, i).getId());
        }

        // Lock every participant account the batch could touch, in the global order. The CCP's
        // rows are never mutated, so they are not locked.
        Map<Long, CashAccount> cashById = new HashMap<>();
        for (Long id : new TreeSet<>(cashIdByParticipant.values())) {
            cashById.put(id, cashAccountRepository.lockById(id).orElseThrow());
        }
        Map<Long, SecurityHolding> holdingById = new HashMap<>();
        for (Long id : new TreeSet<>(holdingIdByKey.values())) {
            holdingById.put(id, securityHoldingRepository.lockById(id).orElseThrow());
        }

        Map<Long, BigDecimal> cashSnapshot = new HashMap<>();
        cashIdByParticipant.forEach((p, id) -> cashSnapshot.put(p, cashById.get(id).getBalance()));
        Map<NetKey, Long> holdingSnapshot = new HashMap<>();
        holdingIdByKey.forEach((k, id) -> holdingSnapshot.put(k, holdingById.get(id).getQuantity()));

        Map<Long, Trade> tradeById = new HashMap<>();
        List<TradeLeg> legs = new ArrayList<>();
        for (Trade t : trades) {
            tradeById.put(t.getId(), t);
            legs.add(new TradeLeg(t.getId(), t.getBuyerId(), t.getSellerId(), t.getInstrumentId(), t.getQuantity(), t.getAmount()));
        }

        Plan plan = BatchNettingPlanner.plan(legs, new BalanceSnapshot(cashSnapshot, holdingSnapshot));
        assertNetsToZero(plan);

        plan.failedTradeReasons().forEach((tradeId, reason) -> tradeById.get(tradeId).markFailed(reason));
        plan.settleable().forEach(leg -> tradeById.get(leg.tradeId()).markSettled());

        Map<Long, BigDecimal> cashOwedByParticipant = new TreeMap<>();
        for (Map.Entry<NetKey, NetAccumulator> e : plan.netPositions().entrySet()) {
            NetKey key = e.getKey();
            long qty = e.getValue().quantity;
            BigDecimal amount = e.getValue().amount;
            if (qty == 0 && amount.signum() == 0) {
                continue;
            }

            NetPosition position = netPositionRepository.save(
                    new NetPosition(batchId, key.participantId(), key.instrumentId(), qty, amount));

            SecurityHolding holding = holdingById.get(holdingIdByKey.get(key));
            if (qty > 0) {
                holding.credit(qty);
            } else if (qty < 0) {
                holding.debit(-qty);
            }
            cashOwedByParticipant.merge(key.participantId(), amount, BigDecimal::add);

            ledgerService.recordNetPosition(position.getId(), holding.getId(), ccpHoldingIdByInstrument.get(key.instrumentId()),
                    cashIdByParticipant.get(key.participantId()), ccpCashId, qty, amount);
        }

        // Cash moves once per participant, net across instruments — matching how the planner
        // checked it, so a participant paid for one instrument and paying for another is never
        // transiently overdrawn.
        cashOwedByParticipant.forEach((participant, owed) -> {
            CashAccount cash = cashById.get(cashIdByParticipant.get(participant));
            if (owed.signum() > 0) {
                cash.debit(owed);
            } else if (owed.signum() < 0) {
                cash.credit(owed.negate());
            }
        });
    }

    /** Defensive: if this ever fails, the exception rolls the entire batch back. */
    private static void assertNetsToZero(Plan plan) {
        Map<Long, Long> quantityByInstrument = new HashMap<>();
        BigDecimal cash = BigDecimal.ZERO;
        for (Map.Entry<NetKey, NetAccumulator> e : plan.netPositions().entrySet()) {
            quantityByInstrument.merge(e.getKey().instrumentId(), e.getValue().quantity, Long::sum);
            cash = cash.add(e.getValue().amount);
        }
        if (cash.signum() != 0 || quantityByInstrument.values().stream().anyMatch(q -> q != 0)) {
            throw new IllegalStateException("net positions do not sum to zero; refusing to settle batch");
        }
    }
}
