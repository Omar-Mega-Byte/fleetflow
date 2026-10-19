# First-party demo login

The FleetFlow `/login` form posts email and password to `/api/v1/auth/login` on the same HTTPS origin. The application checks a BCrypt hash in `local_login_credentials`, issues a random 256-bit opaque token, and stores only its SHA-256 key and actor ID in Redis for 90 minutes. The browser keeps the token in memory and sends it in `X-FleetFlow-Session`; it never stores the password or token in browser storage. Every API request rechecks the credential's enabled flag, actor activity and current database roles. Logout deletes the Redis session. The existing Keycloak authorization-code/PKCE route remains available through “Continue with workspace SSO.”

This login is for separately provisioned synthetic demo accounts. Keycloak passwords do not automatically work here. There is no self-service registration, password reset, MFA or persistent “remember me” session. Do not represent this as final production identity acceptance.

## Provisioning

Provision only after the V15 migration is applied. Generate a unique password of at least 16 characters for each synthetic persona and retain it in an owner-controlled vault outside Git, CI logs and the VM. Generate a BCrypt hash using `LocalPasswordHashTool`, which reads the password from standard input and prints only the hash. The tool is compiled with the app; its classpath needs the project's runtime dependencies.

For each persona, insert or update `local_login_credentials` using the **existing** actor row selected by the exact production issuer and Keycloak subject. Use the account's demo email (lowercase), BCrypt hash and actor ID. A missing or duplicate actor must abort provisioning. Example SQL shape, with parameters supplied privately through `psql` or a prepared operator script:

```sql
INSERT INTO local_login_credentials (actor_id, email, password_hash)
SELECT id, :email, :password_hash
FROM actors WHERE issuer = :issuer AND subject = :subject AND active = true
ON CONFLICT (actor_id) DO UPDATE SET
    email = EXCLUDED.email,
    password_hash = EXCLUDED.password_hash,
    enabled = true,
    updated_at = now();
```

Verify five rows for the five demo subjects without printing hashes. Test one correct login, one wrong password, a role-scoped API request, logout revocation, and SSO before sharing the URL. Rotation replaces a hash and invalidates that account's existing local tokens on the next request. Disabling a credential or actor also blocks subsequent API requests immediately.

## Release boundary

The app image contains the migration and authentication endpoint; the edge image contains the login form and a dedicated login rate limit. Deploy both from the **same reviewed source commit** and immutable image references. Keep the previous app and edge digests available for rollback. Run the candidate validator and all target smoke checks from the production runbook; a visual screenshot alone is not login acceptance. Existing Keycloak/JWT clients continue to use `Authorization: Bearer`.
