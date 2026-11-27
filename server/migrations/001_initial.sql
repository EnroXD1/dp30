CREATE TABLE brigades (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name text NOT NULL UNIQUE CHECK (length(name) BETWEEN 1 AND 120),
    active boolean NOT NULL DEFAULT true,
    version integer NOT NULL DEFAULT 1
);
CREATE TABLE users (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    login text NOT NULL UNIQUE,
    display_name text NOT NULL,
    password_hash text NOT NULL,
    role text NOT NULL CHECK (role IN ('dispatcher','worker','manager','admin')),
    brigade_id bigint REFERENCES brigades,
    active boolean NOT NULL DEFAULT true,
    version integer NOT NULL DEFAULT 1,
    CHECK ((role = 'worker') = (brigade_id IS NOT NULL))
);
CREATE TABLE sessions (
    digest text PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);
CREATE INDEX sessions_user ON sessions(user_id);
CREATE TABLE login_limits (
    login text PRIMARY KEY,
    failures timestamptz[] NOT NULL DEFAULT '{}',
    blocked_until timestamptz
);
CREATE TABLE access_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id bigint REFERENCES users,
    login text NOT NULL,
    kind text NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE requests (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_id bigint REFERENCES requests,
    address text NOT NULL CHECK (length(address) BETWEEN 1 AND 250),
    description text NOT NULL CHECK (length(description) BETWEEN 1 AND 2000),
    priority text NOT NULL CHECK (priority IN ('low','normal','high')),
    due_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'NEW' CHECK (status IN
        ('NEW','ASSIGNED','ACCEPTED','IN_WORK','COMPLETED','CLOSED','CANCELLED')),
    version integer NOT NULL DEFAULT 1,
    result text CHECK (length(result) BETWEEN 1 AND 4000),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (parent_id IS DISTINCT FROM id)
);
CREATE INDEX requests_status_due ON requests(status,due_at,id);
CREATE TABLE assignments (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id bigint NOT NULL REFERENCES requests,
    brigade_id bigint NOT NULL REFERENCES brigades,
    assigned_by bigint NOT NULL REFERENCES users,
    assigned_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    ended_at timestamptz
);
CREATE UNIQUE INDEX one_active_assignment ON assignments(request_id) WHERE ended_at IS NULL;
CREATE INDEX assignments_brigade ON assignments(brigade_id,request_id);
CREATE TABLE request_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id bigint NOT NULL REFERENCES requests,
    actor_id bigint NOT NULL REFERENCES users,
    kind text NOT NULL,
    status text NOT NULL,
    version integer NOT NULL,
    comment text,
    operation_id uuid NOT NULL UNIQUE,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(request_id,version)
);
CREATE TABLE command_receipts (
    operation_id uuid PRIMARY KEY,
    actor_id bigint NOT NULL REFERENCES users,
    request_id bigint NOT NULL REFERENCES requests,
    fingerprint text NOT NULL,
    response jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE admin_audit_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id bigint NOT NULL REFERENCES users,
    entity text NOT NULL,
    entity_id bigint NOT NULL,
    kind text NOT NULL,
    operation_id uuid NOT NULL UNIQUE,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE admin_receipts (
    operation_id uuid PRIMARY KEY,
    actor_id bigint NOT NULL REFERENCES users,
    fingerprint text NOT NULL,
    response jsonb NOT NULL
);
