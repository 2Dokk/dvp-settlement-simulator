-- Batch settlement now commits a whole netted batch in one transaction and books each net
-- position straight to the ledger, so batch ledger entries point at a net position rather than
-- a per-trade settlement instruction.
ALTER TABLE ledger_entries ALTER COLUMN related_instruction_id DROP NOT NULL;
ALTER TABLE ledger_entries ADD COLUMN net_position_id BIGINT REFERENCES net_positions(id);
ALTER TABLE ledger_entries ADD CONSTRAINT ledger_entries_single_source
    CHECK ((related_instruction_id IS NULL) <> (net_position_id IS NULL));

-- Why a trade was left out of its batch (e.g. a counterparty's shortfall).
ALTER TABLE trades ADD COLUMN failure_reason VARCHAR(500);

-- Exactly one CCP participant, created race-free with INSERT ... ON CONFLICT.
CREATE UNIQUE INDEX ux_participants_ccp ON participants (name) WHERE name = 'CCP';
