package com.dvp.settlement.api.dto;

import com.dvp.settlement.domain.LedgerEntry;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class AccountDtos {

    public record DepositCashRequest(@NotNull Long participantId, @NotNull @Positive BigDecimal amount) {
    }

    public record DepositSecuritiesRequest(@NotNull Long participantId, @NotNull Long instrumentId, @Positive long quantity) {
    }

    public record SecurityHoldingResponse(Long instrumentId, String isin, long quantity) {
    }

    public record AccountResponse(Long participantId, String participantName, BigDecimal cashBalance,
                                   List<SecurityHoldingResponse> holdings) {
    }

    public record LedgerEntryResponse(Long id, String accountType, Long accountId, String entryType,
                                       BigDecimal amount, Long relatedInstructionId, Long netPositionId,
                                       Instant createdAt) {
        public static LedgerEntryResponse from(LedgerEntry e) {
            return new LedgerEntryResponse(e.getId(), e.getAccountType().name(), e.getAccountId(),
                    e.getEntryType().name(), e.getAmount(), e.getRelatedInstructionId(), e.getNetPositionId(),
                    e.getCreatedAt());
        }
    }
}
