package com.dvp.settlement.service;

import com.dvp.settlement.domain.CashAccount;
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

    /** Name of the reserved central-counterparty participant that netted batches settle against. */
    public static final String CCP_NAME = "CCP";

    private final ParticipantRepository participantRepository;
    private final CashAccountRepository cashAccountRepository;
    private final SecurityHoldingRepository securityHoldingRepository;

    @Transactional
    public CashAccount getOrCreateCashAccount(Long participantId, String currency) {
        cashAccountRepository.insertIfAbsent(participantId, currency);
        return cashAccountRepository.findByParticipantIdAndCurrency(participantId, currency).orElseThrow();
    }

    @Transactional
    public SecurityHolding getOrCreateSecurityHolding(Long participantId, Long instrumentId) {
        securityHoldingRepository.insertIfAbsent(participantId, instrumentId);
        return securityHoldingRepository.findByParticipantIdAndInstrumentId(participantId, instrumentId).orElseThrow();
    }

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
    public Long ccpParticipantId() {
        participantRepository.insertCcpIfAbsent();
        Long ccpId = participantRepository.findByName(CCP_NAME).orElseThrow().getId();
        getOrCreateCashAccount(ccpId, DEFAULT_CURRENCY);
        return ccpId;
    }
}
