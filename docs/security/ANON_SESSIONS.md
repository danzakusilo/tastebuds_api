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
- Set `TASTEBUDS_ANONSESSION_SIGNINGKEYS_<n>_ID` and `TASTEBUDS_ANONSESSION_SIGNINGKEYS_<n>_SECRET` from a secret.
  `<n>` is the index of the key in the list: index 0 signs new tokens, and every key in the list verifies. The indices
  must run 0, 1, 2, ... with no gap and no leading zero (`_01_` is not index 1). A gap or a leading zero leaves
  variables unbound, and the `api` role fails at startup. It also fails at startup without any key.
- Generate a secret with `openssl rand -base64 32 | tr -d '\n'`. The value must be one line without a trailing
  newline: the Base64 decoder rejects the newline that `openssl rand -base64 32` writes, and startup fails with
  `tastebuds.anon-session.signing-keys[<n>].secret must be Base64 of at least 32 bytes`. A Secret created with
  `kubectl create secret generic --from-file` keeps the newline at the end of the file.
- Every `api` replica needs the same keys. The `worker` role needs none.
- `tastebuds.anon-session.ephemeral-signing-key=true` gives a random key per start. Use it for local development and
  tests only: tokens stop working on restart and are not shared between replicas.

## Rotation
Rotation takes three deploys. During a rolling deploy old and new replicas serve at the same time. No replica may
receive a token signed with a key it does not know, because a 401 makes the client drop its session.

1. Add the new key at index 1. It verifies only. Deploy to every `api` replica.
2. Move the new key to index 0 and the old key to index 1. Deploy.
3. Wait until the step 2 rollout has finished on every `api` replica, then wait one token lifetime and one hour more
   (a margin for old pods that are still shutting down). Then remove the old key. Deploy.

The token lifetime is `tastebuds.anon-session.ttl` (environment variable `TASTEBUDS_ANONSESSION_TTL`): 30 days by
default, from 1 hour to 90 days. Replicas of the old release sign with the old key until the step 2 rollout ends, and
each token keeps the lifetime that was set when it was issued. If the lifetime changed while the old key signed, wait
for the longest value.

## Leaked key
Take the leaked key out of service at once, but always deploy a valid key list:

1. Write the key list again without the leaked key. Move the remaining keys down so that the indices run 0, 1, 2, ...
   with no gap. If no key remains, put a new key at index 0.
2. Deploy to every `api` replica.

Deleting only the variables of the leaked key works only when it has the highest index and another key remains. In
every other case the list has a gap or no key: the new `api` pods fail at startup and the rollout stops (the Helm
chart rolls out with `maxUnavailable: 0`). The old pods keep serving with the list they started with: they accept the
leaked key, and if it is at index 0 they also sign new tokens with it. The chart reads every key from one Secret, so
an old pod that restarts after the Secret was edited reads the broken list and fails at startup too.

Every session signed with the leaked key ends, and clients create new ones. During the rollout, old pods reject tokens
signed with a key that only the new pods know, and new pods reject tokens signed with the leaked key. A client that
gets a 401 creates a new session, so a client can lose its session more than once during the rollout.

## Limits
- No revocation before Phase 4: a stolen token works until it expires.
- No renewal.
- Per-session budgets are later work (docs/PLAN.md §5.6).
