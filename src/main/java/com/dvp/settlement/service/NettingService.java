package com.dvp.settlement.service;

import com.dvp.settlement.domain.*;
import com.dvp.settlement.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Nets a day's trades down to one settlement instruction per (participant, instrument), routed
 * through a reserved CCP participant instead of matching arbitrary counterparties pairwise.
 * Because every trade contributes +quantity to its buyer and -quantity to its seller for the
 * same instrument, the sum of all participants' net positions for an instrument is always zero —
 * so the CCP's own exposure nets out once every instruction in the batch has settled.
 */
@Service
@RequiredArgsConstructor
public class NettingService {

    private final SettlementBatchRepository batchRepository;
    private final TradeRepository tradeRepository;
    private final NetPositionRepository netPositionRepository;
    private final SettlementInstructionRepository instructionRepository;
    private final AccountService accountService;

    /**
     * Claims every PENDING trade for {@code settlementDate} into a new batch and computes net
     * positions + settlement instructions. Does not execute any instruction — see
     * {@link SettlementOrchestrator#runBatch(LocalDate)}.
     */
    @Transactional
    public SettlementBatch createAndNetBatch(LocalDate settlementDate) {
        SettlementBatch batch = batchRepository.save(new SettlementBatch(settlementDate));

        int claimed = tradeRepository.claimPendingForBatch(settlementDate, batch.getId());
        if (claimed == 0) {
            batch.setStatus(BatchStatus.COMPLETED);
            batch.setCompletedAt(Instant.now());
            return batch;
        }

        List<Trade> trades = tradeRepository.findByBatchId(batch.getId());
        Map<NetKey, NetAccumulator> net = new HashMap<>();
        for (Trade trade : trades) {
            net.computeIfAbsent(new NetKey(trade.getBuyerId(), trade.getInstrumentId()), k -> new NetAccumulator())
                    .addAsBuyer(trade.getQuantity(), trade.getAmount());
            net.computeIfAbsent(new NetKey(trade.getSellerId(), trade.getInstrumentId()), k -> new NetAccumulator())
                    .addAsSeller(trade.getQuantity(), trade.getAmount());
        }

        Long ccpId = accountService.getOrCreateCcpParticipantId();

        for (Map.Entry<NetKey, NetAccumulator> entry : net.entrySet()) {
            NetKey key = entry.getKey();
            NetAccumulator acc = entry.getValue();
            if (acc.quantity == 0) {
                // Fully offset within the batch — nothing to move. Any residual cash amount from
                // differing trade prices is out of scope for this simulator (documented in README).
                continue;
            }

            accountService.getOrCreateSecurityHolding(key.participantId(), key.instrumentId());
            accountService.getOrCreateSecurityHolding(ccpId, key.instrumentId());
            netPositionRepository.save(new NetPosition(batch.getId(), key.participantId(), key.instrumentId(), acc.quantity, acc.amount));

            SettlementInstruction instruction = acc.quantity > 0
                    ? SettlementInstruction.forNetPositions(batch.getId(), ccpId, key.participantId(), key.instrumentId(), acc.quantity, acc.amount)
                    : SettlementInstruction.forNetPositions(batch.getId(), key.participantId(), ccpId, key.instrumentId(), -acc.quantity, acc.amount.negate());
            instructionRepository.save(instruction);
        }

        batch.setStatus(BatchStatus.NETTED);
        return batch;
    }

    /**
     * Reconciles each claimed trade's status against the settlement instructions that actually
     * carried its buyer's and seller's net exposure, and marks the batch COMPLETED. Call once
     * every instruction from {@link #createAndNetBatch} has been executed.
     */
    @Transactional
    public void finalizeBatch(Long batchId) {
        SettlementBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("settlement batch not found: " + batchId));

        Long ccpId = accountService.getOrCreateCcpParticipantId();
        List<SettlementInstruction> instructions = instructionRepository.findByBatchId(batchId);
        Map<NetKey, SettlementInstruction> byParticipant = new HashMap<>();
        for (SettlementInstruction instruction : instructions) {
            Long participantSide = ccpId.equals(instruction.getFromParticipantId())
                    ? instruction.getToParticipantId()
                    : instruction.getFromParticipantId();
            byParticipant.put(new NetKey(participantSide, instruction.getInstrumentId()), instruction);
        }

        for (Trade trade : tradeRepository.findByBatchId(batchId)) {
            SettlementInstruction buyerSide = byParticipant.get(new NetKey(trade.getBuyerId(), trade.getInstrumentId()));
            SettlementInstruction sellerSide = byParticipant.get(new NetKey(trade.getSellerId(), trade.getInstrumentId()));
            boolean buyerOk = buyerSide == null || buyerSide.getStatus() == InstructionStatus.SETTLED;
            boolean sellerOk = sellerSide == null || sellerSide.getStatus() == InstructionStatus.SETTLED;
            trade.setStatus(buyerOk && sellerOk ? TradeStatus.SETTLED : TradeStatus.FAILED);
        }

        batch.setStatus(BatchStatus.COMPLETED);
        batch.setCompletedAt(Instant.now());
    }
}
