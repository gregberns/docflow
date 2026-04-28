-- Test-only stubs for cross-fragment FK targets. The C3 fragments FK to
-- stored_documents (C2-owned) and documents (C4-owned). For fragment-level
-- ITs we provide minimal stand-ins so the C3 fragment can load standalone.
-- C7.4's V1__init.sql assembly will replace these with the real fragments.

CREATE TABLE stored_documents (
    id UUID PRIMARY KEY
);

CREATE TABLE documents (
    id UUID PRIMARY KEY
);
