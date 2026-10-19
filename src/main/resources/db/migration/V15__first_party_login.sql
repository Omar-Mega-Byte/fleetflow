CREATE TABLE local_login_credentials (
    actor_id uuid PRIMARY KEY REFERENCES actors(id) ON DELETE CASCADE,
    email varchar(320) NOT NULL,
    password_hash varchar(100) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_local_login_email CHECK (email = lower(btrim(email)) AND length(email) BETWEEN 3 AND 320),
    CONSTRAINT ck_local_login_hash CHECK (password_hash LIKE '$2%')
);

CREATE UNIQUE INDEX uk_local_login_email ON local_login_credentials (email);
