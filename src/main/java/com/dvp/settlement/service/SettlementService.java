package com.dvp.settlement.service;

import com.dvp.settlement.domain.*;
import com.dvp.settlement.repository.CashAccountRepository;
import com.dvp.settlement.repository.SecurityHoldingRepository;
import com.dvp.settlement.repository.SettlementInstructionRepository;
import com.dvp.settlement.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;

/**
 * Executes one {@link SettlementInstruction} as an atomic DVP transfer: the securities leg and
 * the cash leg either both apply or neither does. This is the "commit" analogy for the KSD
 * narrative — settleInstruction is a single database transaction, and Spring rolls the whole
 * thing back if anything after the pre-validation step throws.
 *
 * <p>Locking strategy: every account row touched by this instruction is locked with
 * {@code SELECT ... FOR UPDATE}, always in the fixed order (CASH accounts by ascending id, then
 * SECURITIES holdings by ascending id). As long as every caller uses this same order, two
 * concurrent settlements can never form a lock-wait cycle, so no deadlock is possible — we get
 * serializable-looking behaviour on the accounts involved without needing SERIALIZABLE isolation.
 *
 * <p>CCP exemption: netted-batch instructions (see NettingService) route through a reserved CCP
 * participant instead of being matched pairwise. Because each instruction still moves both legs
 * atomically, and a batch alternates "who pays" between deliveries and receipts, an unfunded CCP
 * can't necessarily satisfy the very first instruction of a batch from either side. Real CCPs
 * solve this with pre-funded default/guarantee funds; here we model the same idea by letting the
 * CCP's own balances go negative mid-batch (via debitUnchecked) while every other participant is
 * still held to a strict non-negative floor. Because a batch's net positions always sum to zero
 * per instrument (every trade contributes +qty to one participant and -qty to another), the CCP
 * is mathematically guaranteed to end a fully-successful batch back at zero on both legs.
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementInstructionRepository instructionRepository;
    private final CashAccountRepository cashAccountRepository;
    private final SecurityHoldingRepository securityHoldingRepository;
    private final TradeRepository tradeRepository;
    private final AccountService accountService;
    private final LedgerService ledgerService;

    @Transactional
    public SettlementInstruction settleInstruction(Long instructionId) {
        SettlementInstruction instruction = instructionRepository.lockById(instructionId)
                .orElseThrow(() -> new NoSuchElementException("settlement instruction not found: " + instructionId));

        if (instruction.getStatus() != InstructionStatus.PENDING) {
            // Already settled/failed by a previous call — safe to no-op (idempotent).
            return instruction;
        }

        // Resolve the account ids first (creating rows on demand is cheap and doesn't need
        // locking), then re-fetch every row WITH a lock, always in ascending-id order within
        // each account type. Locking by id (not by "buyer then seller") is what guarantees two
        // concurrent settlements acquire locks in the same global order.
        Long buyerCashId = accountService.getOrCreateCashAccount(instruction.getToParticipantId(), AccountService.DEFAULT_CURRENCY).getId();
        Long sellerCashId = accountService.getOrCreateCashAccount(instruction.getFromParticipantId(), AccountService.DEFAULT_CURRENCY).getId();
        Long buyerHoldingId = accountService.getOrCreateSecurityHolding(instruction.getToParticipantId(), instruction.getInstrumentId()).getId();
        Long sellerHoldingId = accountService.getOrCreateSecurityHolding(instruction.getFromParticipantId(), instruction.getInstrumentId()).getId();

        Map<Long, CashAccount> lockedCash = new TreeMap<>();
        for (Long id : orderedDistinct(buyerCashId, sellerCashId)) {
            lockedCash.put(id, cashAccountRepository.lockById(id).orElseThrow());
        }
        Map<Long, SecurityHolding> lockedHoldings = new TreeMap<>();
        for (Long id : orderedDistinct(buyerHoldingId, sellerHoldingId)) {
            lockedHoldings.put(id, securityHoldingRepository.lockById(id).orElseThrow());
        }

        CashAccount buyerCash = lockedCash.get(buyerCashId);
        CashAccount sellerCash = lockedCash.get(sellerCashId);
        SecurityHolding buyerHolding = lockedHoldings.get(buyerHoldingId);
        SecurityHolding sellerHolding = lockedHoldings.get(sellerHoldingId);

        Long ccpId = accountService.getOrCreateCcpParticipantId();
        boolean sellerIsCcp = ccpId.equals(instruction.getFromParticipantId());
        boolean buyerIsCcp = ccpId.equals(instruction.getToParticipantId());

        // Pre-validate BOTH legs before mutating anything, so a failure never leaves a partial
        // movement pending in the persistence context — this is what makes "fail" mean
        // "nothing moved" without relying on a full transaction rollback. The CCP side of a
        // netted instruction is exempt — see the class doc.
        String failureReason = null;
        if (!sellerIsCcp && sellerHolding.getQuantity() < instruction.getQuantity()) {
            failureReason = "insufficient securities: holding=%d, required=%d"
                    .formatted(sellerHolding.getQuantity(), instruction.getQuantity());
        } else if (!buyerIsCcp && buyerCash.getBalance().compareTo(instruction.getAmount()) < 0) {
            failureReason = "insufficient cash: balance=%s, required=%s"
                    .formatted(buyerCash.getBalance(), instruction.getAmount());
        }

        if (failureReason != null) {
            instruction.markFailed(failureReason);
            propagateTradeStatus(instruction, TradeStatus.FAILED);
            return instruction;
        }

        if (sellerIsCcp) {
            sellerHolding.debitUnchecked(instruction.getQuantity());
        } else {
            sellerHolding.debit(instruction.getQuantity());
        }
        buyerHolding.credit(instruction.getQuantity());
        if (buyerIsCcp) {
            buyerCash.debitUnchecked(instruction.getAmount());
        } else {
            buyerCash.debit(instruction.getAmount());
        }
        sellerCash.credit(instruction.getAmount());

        instruction.markSettled();
        propagateTradeStatus(instruction, TradeStatus.SETTLED);

        ledgerService.recordSettlement(instruction.getId(), sellerHolding.getId(), buyerHolding.getId(),
                buyerCash.getId(), sellerCash.getId(), instruction.getQuantity(), instruction.getAmount());

        return instruction;
    }

    private void propagateTradeStatus(SettlementInstruction instruction, TradeStatus status) {
        if (instruction.getTradeId() != null) {
            tradeRepository.findById(instruction.getTradeId()).ifPresent(trade -> trade.setStatus(status));
        }
    }

    private static List<Long> orderedDistinct(Long a, Long b) {
        return a.equals(b) ? List.of(a) : (a < b ? List.of(a, b) : List.of(b, a));
    }
}
