package com.dvp.settlement.service;

import com.dvp.settlement.domain.CashAccount;
import com.dvp.settlement.domain.Participant;
import com.dvp.settlement.domain.SecurityHolding;
import com.dvp.settlement.repository.CashAccountRepository;
import com.dvp.settlement.repository.ParticipantRepository;
import com.dvp.settlement.repository.SecurityHoldingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class AccountService {

    /** Every account in this simulator settles in a single currency, matching the KSD/DVP demo scope. */
    public static final String DEFAULT_CURRENCY = "KRW";

    /** Name of the reserved central-counterparty participant used to settle netted batches. */
    public static final String CCP_NAME = "CCP";

    private final ParticipantRepository participantRepository;
    private final CashAccountRepository cashAccountRepository;
    private final SecurityHoldingRepository securityHoldingRepository;

    @Transactional
    public CashAccount getOrCreateCashAccount(Long participantId, String currency) {
        return cashAccountRepository.findByParticipantIdAndCurrency(participantId, currency)
                .orElseGet(() -> cashAccountRepository.save(new CashAccount(participantId, currency, BigDecimal.ZERO)));
    }

    @Transactional
    public SecurityHolding getOrCreateSecurityHolding(Long participantId, Long instrumentId) {
        return securityHoldingRepository.findByParticipantIdAndInstrumentId(participantId, instrumentId)
                .orElseGet(() -> securityHoldingRepository.save(new SecurityHolding(participantId, instrumentId, 0L)));
    }

    /**
     * Returns the reserved CCP participant, creating it (with a zero-balance cash account) on
     * first use. All netted settlement instructions route through this participant so that a
     * batch never needs pairwise matching between arbitrary counterparties.
     */
    /** Injects opening cash capital for a participant. Not a DVP movement — no counterparty. */
    @Transactional
    public CashAccount depositCash(Long participantId, BigDecimal amount) {
        CashAccount account = getOrCreateCashAccount(participantId, DEFAULT_CURRENCY);
        account = cashAccountRepository.lockById(account.getId()).orElseThrow();
        account.credit(amount);
        return account;
    }

    /** Injects opening securities holdings for a participant. Not a DVP movement — no counterparty. */
    @Transactional
    public SecurityHolding depositSecurities(Long participantId, Long instrumentId, long quantity) {
        SecurityHolding holding = getOrCreateSecurityHolding(participantId, instrumentId);
        holding = securityHoldingRepository.lockById(holding.getId()).orElseThrow();
        holding.credit(quantity);
        return holding;
    }

    @Transactional
    public Long getOrCreateCcpParticipantId() {
        Participant ccp = participantRepository.findByName(CCP_NAME)
                .orElseGet(() -> participantRepository.save(new Participant(CCP_NAME)));
        getOrCreateCashAccount(ccp.getId(), DEFAULT_CURRENCY);
        return ccp.getId();
    }
}
