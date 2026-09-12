package com.dvp.settlement.api.dto;

import com.dvp.settlement.domain.NetPosition;
import com.dvp.settlement.domain.SettlementBatch;
import com.dvp.settlement.domain.SettlementInstruction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class SettlementDtos {

    public record SettlementInstructionResponse(Long id, Long batchId, Long tradeId, Long fromParticipantId,
                                                  Long toParticipantId, Long instrumentId, long quantity,
                                                  BigDecimal amount, String status, String failureReason) {
        public static SettlementInstructionResponse from(SettlementInstruction i) {
            return new SettlementInstructionResponse(i.getId(), i.getBatchId(), i.getTradeId(),
                    i.getFromParticipantId(), i.getToParticipantId(), i.getInstrumentId(), i.getQuantity(),
                    i.getAmount(), i.getStatus().name(), i.getFailureReason());
        }
    }

    public record NetPositionResponse(Long participantId, Long instrumentId, long netQuantity, BigDecimal netAmount) {
        public static NetPositionResponse from(NetPosition p) {
            return new NetPositionResponse(p.getParticipantId(), p.getInstrumentId(), p.getNetQuantity(), p.getNetAmount());
        }
    }

    public record SettlementBatchResponse(Long id, LocalDate settlementDate, String status,
                                           List<NetPositionResponse> netPositions,
                                           List<SettlementInstructionResponse> instructions) {
        public static SettlementBatchResponse from(SettlementBatch batch, List<NetPosition> positions,
                                                    List<SettlementInstruction> instructions) {
            return new SettlementBatchResponse(batch.getId(), batch.getSettlementDate(), batch.getStatus().name(),
                    positions.stream().map(NetPositionResponse::from).toList(),
                    instructions.stream().map(SettlementInstructionResponse::from).toList());
        }
    }
}
