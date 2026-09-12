package com.dvp.settlement.service;

import com.dvp.settlement.domain.SettlementBatch;
import com.dvp.settlement.domain.SettlementInstruction;
import com.dvp.settlement.repository.SettlementInstructionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Coordinates multi-step settlement flows where each step must be its own database transaction
 * (so a failure in one instruction can't roll back another). Not itself {@code @Transactional} —
 * every call here goes through a Spring proxy on a different bean, so each step really does
 * commit independently.
 */
@Service
@RequiredArgsConstructor
public class SettlementOrchestrator {

    private final TradeService tradeService;
    private final SettlementService settlementService;
    private final NettingService nettingService;
    private final AccountService accountService;
    private final SettlementInstructionRepository instructionRepository;

    /** Registers no batch — settles a single trade immediately, DVP-atomic, no netting. */
    public SettlementInstruction settleTradeNow(Long tradeId) {
        Long instructionId = tradeService.createInstructionForTrade(tradeId);
        return settlementService.settleInstruction(instructionId);
    }

    /**
     * Nets every PENDING trade for {@code settlementDate}, then executes the resulting
     * instructions: all deliveries INTO the CCP first, then all receipts OUT OF the CCP. The CCP
     * is exempt from the non-negative balance check (see SettlementService), so this ordering
     * isn't required for correctness — but running deliveries first still minimizes how far
     * negative the CCP's own balances transiently go during a batch.
     */
    public SettlementBatch runBatch(LocalDate settlementDate) {
        SettlementBatch batch = nettingService.createAndNetBatch(settlementDate);
        Long ccpId = accountService.getOrCreateCcpParticipantId();

        List<SettlementInstruction> instructions = instructionRepository.findByBatchId(batch.getId());
        Comparator<SettlementInstruction> deliveriesFirst =
                Comparator.comparing(i -> ccpId.equals(i.getToParticipantId()) ? 0 : 1);

        instructions.stream()
                .sorted(deliveriesFirst)
                .forEach(i -> settlementService.settleInstruction(i.getId()));

        nettingService.finalizeBatch(batch.getId());
        return batch;
    }
}
