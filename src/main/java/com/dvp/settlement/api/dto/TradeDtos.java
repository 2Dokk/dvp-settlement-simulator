package com.dvp.settlement.api.dto;

import com.dvp.settlement.domain.Trade;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public class TradeDtos {

    public record CreateTradeRequest(@NotNull Long buyerId, @NotNull Long sellerId, @NotNull Long instrumentId,
                                      @Positive long quantity, @NotNull @Positive BigDecimal price,
                                      @NotNull LocalDate tradeDate, @NotNull LocalDate settlementDate) {
    }

    public record TradeResponse(Long id, Long buyerId, Long sellerId, Long instrumentId, long quantity,
                                 BigDecimal price, BigDecimal amount, LocalDate tradeDate,
                                 LocalDate settlementDate, String status, Long batchId) {
        public static TradeResponse from(Trade t) {
            return new TradeResponse(t.getId(), t.getBuyerId(), t.getSellerId(), t.getInstrumentId(),
                    t.getQuantity(), t.getPrice(), t.getAmount(), t.getTradeDate(), t.getSettlementDate(),
                    t.getStatus().name(), t.getBatchId());
        }
    }
}
