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
 * Settles a single trade immediately as one atomic DVP transfer (no netting): the securities leg
 * and the cash leg either both apply or neither does, inside one database transaction.
 *
 * <p>Lock order, shared with {@link BatchSettlementService} so the two paths can never deadlock
 * each other: trade row first, then CASH accounts by ascending id, then SECURITIES holdings by
 * ascending id.
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

        Trade trade = null;
        if (instruction.getTradeId() != null) {
            trade = tradeRepository.lockById(instruction.getTradeId()).orElseThrow();
            if (trade.getStatus() != TradeStatus.PENDING) {
                // A settlement batch claimed this trade after the instruction was created.
                instruction.markFailed("trade %d is no longer PENDING (status=%s)".formatted(trade.getId(), trade.getStatus()));
                return instruction;
            }
        }

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

        // Pre-validate BOTH legs before mutating anything, so a failure never leaves a partial
        // movement pending in the persistence context.
        String failureReason = null;
        if (sellerHolding.getQuantity() < instruction.getQuantity()) {
            failureReason = "insufficient securities: holding=%d, required=%d"
                    .formatted(sellerHolding.getQuantity(), instruction.getQuantity());
        } else if (buyerCash.getBalance().compareTo(instruction.getAmount()) < 0) {
            failureReason = "insufficient cash: balance=%s, required=%s"
                    .formatted(buyerCash.getBalance(), instruction.getAmount());
        }

        if (failureReason != null) {
            instruction.markFailed(failureReason);
            if (trade != null) {
                trade.markFailed(failureReason);
            }
            return instruction;
        }

        sellerHolding.debit(instruction.getQuantity());
        buyerHolding.credit(instruction.getQuantity());
        buyerCash.debit(instruction.getAmount());
        sellerCash.credit(instruction.getAmount());

        instruction.markSettled();
        if (trade != null) {
            trade.markSettled();
        }

        ledgerService.recordSettlement(instruction.getId(), sellerHolding.getId(), buyerHolding.getId(),
                buyerCash.getId(), sellerCash.getId(), instruction.getQuantity(), instruction.getAmount());

        return instruction;
    }

    private static List<Long> orderedDistinct(Long a, Long b) {
        return a.equals(b) ? List.of(a) : (a < b ? List.of(a, b) : List.of(b, a));
    }
}
