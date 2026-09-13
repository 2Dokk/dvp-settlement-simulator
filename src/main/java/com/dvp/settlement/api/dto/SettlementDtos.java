package com.dvp.settlement.api.dto;

import com.dvp.settlement.domain.NetPosition;
import com.dvp.settlement.domain.SettlementBatch;
import com.dvp.settlement.domain.SettlementInstruction;
import com.dvp.settlement.domain.Trade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class SettlementDtos {

    public record SettlementInstructionResponse(Long id, Long tradeId, Long fromParticipantId,
                                                Long toParticipantId, Long instrumentId, long quantity,
                                                BigDecimal amount, String status, String failureReason) {
        public static SettlementInstructionResponse from(SettlementInstruction i) {
            return new SettlementInstructionResponse(i.getId(), i.getTradeId(),
                    i.getFromParticipantId(), i.getToParticipantId(), i.getInstrumentId(), i.getQuantity(),
                    i.getAmount(), i.getStatus().name(), i.getFailureReason());
        }
    }

    public record NetPositionResponse(Long participantId, Long instrumentId, long netQuantity, BigDecimal netAmount) {
        public static NetPositionResponse from(NetPosition p) {
            return new NetPositionResponse(p.getParticipantId(), p.getInstrumentId(), p.getNetQuantity(), p.getNetAmount());
        }
    }

    public record BatchTradeResponse(Long tradeId, String status, String failureReason) {
        public static BatchTradeResponse from(Trade t) {
            return new BatchTradeResponse(t.getId(), t.getStatus().name(), t.getFailureReason());
        }
    }

    public record SettlementBatchResponse(Long id, LocalDate settlementDate, String status,
                                          List<NetPositionResponse> netPositions,
                                          List<BatchTradeResponse> trades) {
        public static SettlementBatchResponse from(SettlementBatch batch, List<NetPosition> positions, List<Trade> trades) {
            return new SettlementBatchResponse(batch.getId(), batch.getSettlementDate(), batch.getStatus().name(),
                    positions.stream().map(NetPositionResponse::from).toList(),
                    trades.stream().map(BatchTradeResponse::from).toList());
        }
    }
}
