package com.dvp.settlement.api;

import com.dvp.settlement.domain.AccountType;
import com.dvp.settlement.domain.CashAccount;
import com.dvp.settlement.domain.Instrument;
import com.dvp.settlement.domain.Participant;
import com.dvp.settlement.domain.SecurityHolding;
import com.dvp.settlement.repository.InstrumentRepository;
import com.dvp.settlement.repository.LedgerEntryRepository;
import com.dvp.settlement.repository.ParticipantRepository;
import com.dvp.settlement.repository.SecurityHoldingRepository;
import com.dvp.settlement.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static com.dvp.settlement.api.dto.AccountDtos.*;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final ParticipantRepository participantRepository;
    private final InstrumentRepository instrumentRepository;
    private final SecurityHoldingRepository securityHoldingRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountService accountService;

    @PostMapping("/deposit-cash")
    public AccountResponse depositCash(@Valid @RequestBody DepositCashRequest request) {
        accountService.depositCash(request.participantId(), request.amount());
        return get(request.participantId());
    }

    @PostMapping("/deposit-securities")
    public AccountResponse depositSecurities(@Valid @RequestBody DepositSecuritiesRequest request) {
        accountService.depositSecurities(request.participantId(), request.instrumentId(), request.quantity());
        return get(request.participantId());
    }

    @GetMapping("/{participantId}")
    public AccountResponse get(@PathVariable Long participantId) {
        Participant participant = participantRepository.findById(participantId)
                .orElseThrow(() -> new NoSuchElementException("unknown participant: " + participantId));

        BigDecimal cashBalance = accountService.getOrCreateCashAccount(participantId, AccountService.DEFAULT_CURRENCY).getBalance();

        Map<Long, String> isinByInstrumentId = instrumentRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Instrument::getId, Instrument::getIsin));

        List<SecurityHoldingResponse> holdings = securityHoldingRepository.findByParticipantId(participantId).stream()
                .filter(h -> h.getQuantity() != 0)
                .map(h -> new SecurityHoldingResponse(h.getInstrumentId(), isinByInstrumentId.get(h.getInstrumentId()), h.getQuantity()))
                .toList();

        return new AccountResponse(participantId, participant.getName(), cashBalance, holdings);
    }

    @GetMapping("/{participantId}/ledger")
    public List<LedgerEntryResponse> ledger(@PathVariable Long participantId) {
        CashAccount cashAccount = accountService.getOrCreateCashAccount(participantId, AccountService.DEFAULT_CURRENCY);
        List<LedgerEntryResponse> entries = new java.util.ArrayList<>(
                ledgerEntryRepository.findByAccountTypeAndAccountIdOrderByCreatedAtAsc(AccountType.CASH, cashAccount.getId())
                        .stream().map(LedgerEntryResponse::from).toList());

        for (SecurityHolding holding : securityHoldingRepository.findByParticipantId(participantId)) {
            entries.addAll(ledgerEntryRepository
                    .findByAccountTypeAndAccountIdOrderByCreatedAtAsc(AccountType.SECURITIES, holding.getId())
                    .stream().map(LedgerEntryResponse::from).toList());
        }

        return entries.stream().sorted(Comparator.comparing(LedgerEntryResponse::createdAt)).toList();
    }
}
