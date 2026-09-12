CREATE TABLE participants (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE instruments (
    id          BIGSERIAL PRIMARY KEY,
    isin        VARCHAR(12) NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE cash_accounts (
    id              BIGSERIAL PRIMARY KEY,
    participant_id  BIGINT NOT NULL REFERENCES participants(id),
    currency        VARCHAR(3) NOT NULL,
    balance         NUMERIC(19, 2) NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0,
    UNIQUE (participant_id, currency)
);

CREATE TABLE securities_holdings (
    id              BIGSERIAL PRIMARY KEY,
    participant_id  BIGINT NOT NULL REFERENCES participants(id),
    instrument_id   BIGINT NOT NULL REFERENCES instruments(id),
    quantity        BIGINT NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0,
    UNIQUE (participant_id, instrument_id)
);

CREATE TABLE settlement_batches (
    id                  BIGSERIAL PRIMARY KEY,
    settlement_date     DATE NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    completed_at        TIMESTAMP
);

CREATE TABLE trades (
    id                  BIGSERIAL PRIMARY KEY,
    buyer_id            BIGINT NOT NULL REFERENCES participants(id),
    seller_id           BIGINT NOT NULL REFERENCES participants(id),
    instrument_id       BIGINT NOT NULL REFERENCES instruments(id),
    quantity            BIGINT NOT NULL CHECK (quantity > 0),
    price               NUMERIC(19, 4) NOT NULL CHECK (price > 0),
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    trade_date          DATE NOT NULL,
    settlement_date     DATE NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    batch_id            BIGINT REFERENCES settlement_batches(id),
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_trades_settlement_date_status ON trades (settlement_date, status);

CREATE TABLE net_positions (
    id              BIGSERIAL PRIMARY KEY,
    batch_id        BIGINT NOT NULL REFERENCES settlement_batches(id),
    participant_id  BIGINT NOT NULL REFERENCES participants(id),
    instrument_id   BIGINT NOT NULL REFERENCES instruments(id),
    net_quantity    BIGINT NOT NULL,
    net_amount      NUMERIC(19, 2) NOT NULL
);

CREATE TABLE settlement_instructions (
    id                      BIGSERIAL PRIMARY KEY,
    batch_id                BIGINT REFERENCES settlement_batches(id),
    trade_id                BIGINT REFERENCES trades(id),
    from_participant_id     BIGINT NOT NULL REFERENCES participants(id),
    to_participant_id       BIGINT NOT NULL REFERENCES participants(id),
    instrument_id           BIGINT NOT NULL REFERENCES instruments(id),
    quantity                BIGINT NOT NULL CHECK (quantity > 0),
    amount                  NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    failure_reason          VARCHAR(500),
    created_at              TIMESTAMP NOT NULL DEFAULT now(),
    settled_at              TIMESTAMP
);

CREATE TABLE ledger_entries (
    id                          BIGSERIAL PRIMARY KEY,
    account_type                VARCHAR(20) NOT NULL,
    account_id                  BIGINT NOT NULL,
    entry_type                  VARCHAR(10) NOT NULL,
    amount                      NUMERIC(19, 4) NOT NULL,
    related_instruction_id      BIGINT NOT NULL REFERENCES settlement_instructions(id),
    created_at                  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_type, account_id);
