-- PARTIALLY_CONFIRMED (19 characters) is a valid intermediate state from V9.
ALTER TABLE reconciliation ALTER COLUMN status TYPE VARCHAR(32);
