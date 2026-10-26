-- MatNotes sync: zero-knowledge schema. The server only ever stores ciphertext and public keys.
-- Every state change takes its change_seq from one sequence while holding the global write lock
-- (see db.writeTransaction), so sequence order equals commit order and the sync cursor cannot skip rows.
CREATE SEQUENCE change_seq;

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 60),
    email text NOT NULL,
    auth_hash text NOT NULL,                 -- scrypt of the client-derived auth key (never the password)
    kdf_salt text NOT NULL,
    kdf_iterations integer NOT NULL CHECK (kdf_iterations >= 100000),
    public_key text NOT NULL,                -- RSA-3072 SPKI, base64
    enc_private_key text NOT NULL,           -- private key wrapped with the password-derived key
    recovery_auth_hash text NOT NULL,
    enc_private_key_recovery text NOT NULL,  -- private key wrapped with the recovery key
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_key ON users (lower(email));

CREATE TABLE refresh_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash text NOT NULL UNIQUE,
    device_name text,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);
CREATE INDEX refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE folders (
    id uuid PRIMARY KEY,                     -- chosen by the client (it is part of the encryption context)
    owner_id uuid REFERENCES users (id) ON DELETE SET NULL,   -- null once the owner deleted the account (folder is then deleted too)
    enc_name text NOT NULL,
    personal boolean NOT NULL DEFAULT false, -- the user's own folder (maps to the app's main folder); at most one per owner
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    change_seq bigint NOT NULL DEFAULT nextval('change_seq')
);
CREATE INDEX folders_owner ON folders (owner_id);
CREATE UNIQUE INDEX folders_one_personal ON folders (owner_id) WHERE personal AND deleted_at IS NULL;

CREATE TABLE folder_members (
    folder_id uuid NOT NULL REFERENCES folders (id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role text NOT NULL CHECK (role IN ('owner', 'editor')),
    status text NOT NULL CHECK (status IN ('invited', 'active', 'removed')),
    wrapped_key text NOT NULL DEFAULT '',    -- folder key wrapped with this member's public key
    invited_by uuid REFERENCES users (id) ON DELETE SET NULL,
    change_seq bigint NOT NULL DEFAULT nextval('change_seq'),
    PRIMARY KEY (folder_id, user_id)
);
CREATE INDEX folder_members_user ON folder_members (user_id, change_seq);

CREATE TABLE docs (
    id uuid PRIMARY KEY,
    folder_id uuid NOT NULL REFERENCES folders (id) ON DELETE CASCADE,
    enc_name text NOT NULL,
    version integer NOT NULL,
    size bigint NOT NULL DEFAULT 0,          -- ciphertext size of the latest version
    created_by uuid REFERENCES users (id) ON DELETE SET NULL,
    updated_by uuid REFERENCES users (id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    change_seq bigint NOT NULL DEFAULT nextval('change_seq')
);
CREATE INDEX docs_folder_seq ON docs (folder_id, change_seq);

-- Only the newest KEEP_VERSIONS versions keep their data; one row per version holds the doc key wrapped with the folder key.
CREATE TABLE doc_versions (
    doc_id uuid NOT NULL REFERENCES docs (id) ON DELETE CASCADE,
    version integer NOT NULL,
    wrapped_key text NOT NULL,
    data bytea NOT NULL,
    size bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (doc_id, version)
);
