-- Documents-only FK stub for tests that load the full C3 fragment alongside
-- the real C1 and C2 fragments. C3's llm_call_audit FKs to documents (C4-owned);
-- the C4 fragment carries other dependencies we don't want to drag in here, so
-- this minimal stand-in stays in place until C7.4 assembles V1__init.sql.

CREATE TABLE documents (
    id UUID PRIMARY KEY
);
