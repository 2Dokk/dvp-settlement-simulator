package com.dvp.settlement.domain;

/**
 * Thrown when a debit would push a cash or securities balance negative.
 * Caught by SettlementService to mark the settlement instruction FAILED and roll back
 * the whole DVP transaction — this is the failure half of the atomicity guarantee.
 */
public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(String message) {
        super(message);
    }
}
