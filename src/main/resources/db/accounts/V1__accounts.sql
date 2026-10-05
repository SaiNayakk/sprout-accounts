-- A Sprout account per user. The PAN is never stored in clear: only masked for display, and as a
-- keyed hash so the same PAN can't open a second account.
CREATE TABLE accounts (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL UNIQUE,
    legal_name  text NOT NULL,
    birth_date  date NOT NULL,
    pan_hash    text NOT NULL UNIQUE,
    pan_masked  text NOT NULL,
    bank_vpa    text NOT NULL,
    status      text NOT NULL CHECK (status IN ('ACTIVE')),
    opened_at   timestamptz NOT NULL
);
