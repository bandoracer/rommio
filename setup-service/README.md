# Rommio one-touch setup

A separate Cloudflare Worker provisions the RomM address and Cloudflare Access
service token after a user approves a device. The iOS app then verifies RomM's
heartbeat and opens RomM account sign-in. This does not provision a shared RomM
user, transfer game saves, or change an existing account's identity.

The default app endpoint serves the personal `romm.borchetta.xyz` deployment.
Other servers can still use manual setup. A self-hosted build can change the
`OneTouchSetupClient` endpoint and approval origin together.

## Contract

1. `POST /device/start` with `{deviceName}` returns `code`, `secret`, `approveUrl`,
   `expiresInSeconds` (600), and `pollIntervalSeconds` (3).
2. Open `approveUrl` in Safari or scan the QR code. Cloudflare Access authenticates
   a member of the same `Allow Gamers` policy used by the RomM application.
3. The user compares the displayed code and explicitly approves or denies.
4. `POST /device/poll` with `{code,secret}` returns `pending`, `denied`, or
   `approved` with `{rommUrl,accessProfile:{type:"serviceToken",clientId,clientSecret}}`.
5. Store the credentials in the profile's secret store and verify RomM; only then
   `POST /device/complete` with `{code,secret}`. Delivery is repeatable to that same
   secret until acknowledgement or expiry, so losing a response is recoverable.
6. Dismissal sends `POST /device/cancel`. Retry allocates a fresh code. Expired,
   cancelled, and acknowledged codes return HTTP 404; wrong secrets return 403.

The public origin only serves the device API. The approval origin only serves
approval pages. The Worker verifies Access JWT signature, issuer, audience,
expiry, and user identity even behind Access. Approval requires a same-origin
POST and a code-specific form nonce. Code storage uses serialized SQLite Durable
Object operations; no service-token secrets are stored in the code table. All
responses are uncached. Polling secrets stay out of query strings and QR codes.
Start requests are limited to ten per minute per source IP (stored as a hash).
There is no production authentication bypass or mock credential path.

Approval HTML uses `Referrer-Policy: same-origin` so browser form submissions
retain the origin needed by the CSRF check. Do not change it to `no-referrer`:
that produces a null Origin on form posts and rejects legitimate approvals.

## Deployment

Use the **Ryan Personal** Cloudflare account in `wrangler.toml`, never the default
Buy Blvd Wrangler login. Required API permissions include Workers Scripts Write,
Workers Routes Write, and Access Apps and Policies Write; provisioning a new
service token also needs Access Service Tokens Write. Custom-domain provisioning
must be allowed for `borchetta.xyz`.

```sh
cd setup-service
npm ci
npm test
# Run these with the personal Cloudflare API credential in the environment.
npx wrangler whoami
npm run deploy
npx wrangler secret put CF_ACCESS_CLIENT_ID
npx wrangler secret put CF_ACCESS_CLIENT_SECRET
```

Before enabling delivery, create a dedicated service token and a `non_identity`
policy allowing that token **only** on the RomM Access application. Do not add it
to the approval app: approval requires an authorized person, not a service token.
Set the two secrets above from that token. Use an appropriate expiration and
record a rotation date. Like Marquee's original flow, approved devices share the
provisioning token; revoking it invalidates all of those devices' edge access.
Individual RomM accounts remain separate. Per-device token issuance/revocation
would require a different contract and additional management permissions.

`ACCESS_AUDIENCE` is the audience of the dedicated approval app,
`rommio-setup.borchetta.xyz`, not RomM's audience or the wildcard application's.
A deployment missing either service-token secret returns 503 on start.

## Validation

`npm test` bundles the production implementation into Miniflare's actual
SQLite-backed Durable Object runtime and exercises approval/delivery,
acknowledgement, denial, cancellation, expiry, rate limits, wrong-secret/host
rejection, CSRF checks, and HTML escaping. JWT tests sign real RSA tokens and
reject wrong issuer/audience, expiration, missing email, and malformed tokens.
Only the test bundle supplies a fake browser identity verifier and expiry hook;
these are absent from the production entry point and deployment bundle.

See `../docs/one-touch-setup-validation.md` for current device and deployment
qualification. Unit tests do not constitute live Cloudflare qualification.
