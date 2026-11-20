# Anonymous sessions

## Purpose
Signed-out users get an anonymous session (tastebuds_spec F01) in Phases 1-3 (docs/PLAN.md D4). A session is a signed
bearer token, not server state. The API verifies it without I/O, so authentication keeps working while Redis is down.

## Client contract
- `POST /v1/sessions/anonymous`: no credentials, no body. Answer: `201` with
  `{"token":"tba1.k1.…","tokenType":"Bearer","expiresAt":"2026-12-20T10:15:30Z"}`.
- Send `Authorization: Bearer <token>` on later requests. Never put the token in a URL.
- A token is live while now < `expiresAt`. The lifetime is 30 days by default, with no renewal.
- Any `401` on a request that carried the token means the token is dead. Drop it and create a new session. The data of
  the old session is not reachable any more.
- A live token on an endpoint without an `ANON_SESSION` rule gets `403`, never `401`.

## Format v1
`tba1.<kid>.<payload>.<mac>`

| Part | Content |
|---|---|
| `tba1` | Format version. A new format gets a new prefix |
| `<kid>` | Id of the signing key, `[a-z0-9]{1,16}`, in clear so verification can pick the key |
| `<payload>` | Base64url without padding of 24 bytes, big-endian: session id (128 bits), `expiresAt` epoch seconds (signed 64 bit). 32 characters |
| `<mac>` | Base64url without padding of HMAC-SHA256(key, US-ASCII bytes of `tba1.<kid>.<payload>`). 43 characters |

Verification order: length at most 128; the whole token matches the format; the key id is known; the signature is equal
(constant time, on the encoded strings); the payload is decoded; the token has not expired. Every failure gives the same
401 problem+json with `WWW-Authenticate: Bearer error="invalid_token"`. Only the wide event records the reason.

## Keys
- Generate a key with `openssl rand -base64 32`.
- Set `TASTEBUDS_ANONSESSION_SIGNINGKEYS_0_ID` and `TASTEBUDS_ANONSESSION_SIGNINGKEYS_0_SECRET` from a secret. More keys
  use `_1_`, `_2_`.
- Every `api` replica needs the same keys. The `worker` role needs none.
- `tastebuds.anon-session.ephemeral-signing-key=true` gives a random key per start. Use it for local development and
  tests only: tokens stop working on restart and are not shared between replicas.

## Rotation
Rotation takes three deploys. During a rolling deploy old and new replicas serve at the same time. No replica may
receive a token signed with a key it does not know, because a 401 makes the client drop its session.

1. Add the new key at index 1. It verifies only. Deploy to every `api` replica.
2. Move the new key to index 0 and the old key to index 1. Deploy.
3. After one TTL, remove the old key. Deploy.

A leaked key: remove it at once. Every session signed with it ends, and clients create new ones.

## Limits
- No revocation before Phase 4: a stolen token works until it expires.
- No renewal.
- Per-session budgets are later work (docs/PLAN.md §5.6).
