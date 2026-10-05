-- The customer's demat account at the depository, opened with their Sprout account. Accounts opened
-- before this have none until they're next read by a service (see Accounts.ensureDemat).
ALTER TABLE accounts ADD COLUMN bo_id text UNIQUE;
