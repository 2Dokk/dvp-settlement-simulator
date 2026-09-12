package com.dvp.settlement.service;

/** (participant, instrument) — the unit that trades get netted down to within one batch. */
record NetKey(Long participantId, Long instrumentId) {
}
