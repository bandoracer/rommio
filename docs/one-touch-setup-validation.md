# One-touch setup qualification — 2026-09-21

## Implemented

- iOS server entry offers one-touch setup alongside manual connection.
- Pairing sheet presents a code, QR, link, same-device Safari approval, status,
  cancellation, and retry.
- Poll results are validated before applying an HTTPS server address and
  profile-scoped Cloudflare credentials. Existing server credentials are never
  sent to the pairing service. Redirects are refused on the production client.
- Credentials use the existing secret store (Keychain on physical iOS; file-backed
  store on this repository's simulator configuration), followed by real RomM
  heartbeat verification before advancing to account sign-in.
- Delivery is retained until successful storage and verification are acknowledged;
  dismissal/restart cannot apply a stale polling response.
- Separate Worker and Access approval app; no changes to Marquee's service.

## Automated results

- 44 iOS tests passed: 24 authentication controller, 11 onboarding/auth flow,
  five setup-client, and four setup-lifecycle tests.
- Six Worker tests passed using Miniflare SQLite Durable Objects and signed JWTs,
  rerun after the live browser fix below.
- iPhone Air simulator build and installation succeeded.

## Live iPhone Air simulator review

Tested on iOS 27 using an isolated application root to preserve the existing
signed-in QA profile. Verified:

1. First-run server entry, setup sheet, live six-character code and QR rendering.
2. Same-device Safari presentation of the Cloudflare Access login page.
3. Authenticated approval in the desktop browser, displaying the matching device
   name and code. This tested cross-device approval; same-device authentication
   completion and a physical camera scan were not exercised.
4. Worker approval, polling delivery, and `/device/complete` all returned 200.
5. The app dismissed both sheets, stored the Cloudflare credentials, verified the
   public RomM heartbeat, and advanced to RomM account sign-in.
6. Entered the QA viewer account through the app UI. Home loaded live collections
   and recently added games through `https://romm.borchetta.xyz`.
7. Terminated and relaunched against the same isolated root. The app returned to
   signed-in Home without setup or login; Library displayed live platforms and
   game counts.

Restored the simulator's original application root after testing.

Earlier simulator review also exercised unavailable-service errors, Try Again,
and Cancel. A start-endpoint 404 now reports service unavailability instead of
incorrectly describing an expired code.

Live browser testing found that `Referrer-Policy: no-referrer` made approval form
posts send a null Origin, failing the exact-origin CSRF check. Approval HTML now
uses `same-origin`; the nonce and exact-origin checks remain enforced. The
regression assertion and live approval both passed after deployment.

## Live deployment

- Account: Ryan Personal, `fa92ae9a6766dab0dde37a83c9d2c006`.
- Worker: `rommio-setup`, version `0715f1b1-0a04-43b6-9421-7c5c5a0c8905`.
- Public device API: `https://rommio-setup.ryan-personal-fa9.workers.dev`.
- Approval domain: `https://rommio-setup.borchetta.xyz`.
- Approval Access app: `df82b29b-583e-4392-9694-5289948b7136`; reuses the existing
  human `Allow Gamers` policy. Audience is in `setup-service/wrangler.toml`.
- Dedicated service token: `Rommio one-touch devices`, ID
  `b6365c02-d46c-494d-bf1e-894c46d411ae`; expires **2027-09-21 07:56:06 UTC**.
  Rotate before expiry. Token values are Worker secrets, not repository content.
- Token policy: `7e94971f-3349-4f7f-b222-aeb1fbb4629a`, service authentication on
  the RomM app only (`4bec96b3-3938-472a-a5ee-00be7b423447`). It does not grant
  access to the approval app. Existing RomM policies were preserved.

The earlier deployment block was resolved by adding Access Service Tokens Write
and Workers Scripts Write for Ryan Personal, plus Workers Routes Write scoped to
`borchetta.xyz`, to the existing personal API token. Existing permissions were
preserved; the API token was not rotated.

Live testing also found the public RomM hostname missing from the healthy
`green-egg` tunnel's published routes. Added `romm.borchetta.xyz` →
`http://192.168.1.221:30062` to tunnel
`51dff16f-3f0e-4b0b-936a-d8d59a6f0f12`, preserving other routes and existing DNS.
The public heartbeat then returned 200 with the dedicated service token and the
app's user agent. RomM itself was serving successfully on the LAN.

## Remaining qualification

- Physical iOS Keychain, camera scan, and same-device browser authentication.
- Android one-touch setup is not implemented by this iPhone-focused change;
  existing manual Cloudflare setup remains available. This is not an Android
  parity claim.
- This validates onboarding and persisted authentication, not gameplay or the
  separate game-save and resume-snapshot sync qualification gates.
