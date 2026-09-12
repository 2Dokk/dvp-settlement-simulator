package com.dvp.settlement.domain;

public enum TradeStatus {
    /** Registered, not yet picked up by a settlement batch. */
    PENDING,
    /** Claimed by a settlement batch's netting run; excluded from further batches. */
    NETTED,
    /** The settlement instruction(s) covering this trade settled successfully. */
    SETTLED,
    /** Settlement was attempted and failed (e.g. insufficient balance). */
    FAILED
}
