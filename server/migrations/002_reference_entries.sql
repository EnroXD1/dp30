CREATE TABLE reference_entries (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind text NOT NULL,
    code text NOT NULL,
    title text NOT NULL,
    active boolean NOT NULL DEFAULT true,
    version integer NOT NULL DEFAULT 1,
    UNIQUE(kind,code)
);
