package com.dvp.settlement.service;

import com.dvp.settlement.domain.SettlementInstruction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Creates and executes a direct (un-netted) settlement instruction as two separate transactions
 * on two different beans, so each step goes through its Spring proxy and commits independently.
 */
@Service
@RequiredArgsConstructor
public class SettlementOrchestrator {

    private final TradeService tradeService;
    private final SettlementService settlementService;

    public SettlementInstruction settleTradeNow(Long tradeId) {
        Long instructionId = tradeService.createInstructionForTrade(tradeId);
        return settlementService.settleInstruction(instructionId);
    }
}
