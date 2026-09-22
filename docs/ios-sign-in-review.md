# iOS sign-in review — September 20, 2026

The iOS onboarding UI now follows Marquee's server-entry → sign-in pattern.
The connection screen contains an address, an optional Access profile sheet,
and Connect. Discovery and heartbeat verification happen behind that action;
returning from browser gateway authentication automatically verifies access.
Successful RomM authentication enters the shell immediately. The underlying
gateway/identity separation and offline profile recovery remain intact.

## Findings fixed

- Mandatory welcome, policy discovery, manual access test, and success pages
  made ordinary login unnecessarily long and exposed implementation concepts.
- Password-grant scopes included `users.read` and `users.write`, which RomM
  reserves for administrators. A real viewer login returned HTTP 403 despite
  valid credentials. Both Android and iOS now request player permissions only.
- iOS displayed a raw NSURLErrorDomain authentication failure; the form now
  displays a readable rejection message.
- Editing the server address clears pending password and gateway credentials,
  and Connect always discovers the current address instead of reusing old results.
- Onboarding's parent accessibility identifier replaced child identifiers;
  an explicit accessibility container preserves identifiers for each control.

## Validation

- iPhone Air, iOS 27 simulator: built, installed, visually inspected server entry
  and sign-in; tapped and typed through real setup against the user's RomM 4.8.1
  server. A dedicated `codex-player-qa` viewer account signed in successfully,
  loaded real library content, and remained signed in after process restart and
  installation of the final build. No existing account credentials were changed.
- iOS: 9 auth-flow tests and 24 AuthSessionController tests passed. Coverage
  includes direct connect, rejection of an unrelated HTML site, changed-server
  discovery, pending credential clearing, and non-admin token scopes.
- Android: 9 targeted auth tests passed via `testStandardDebugUnitTest --tests
  '*Auth*'`; the shared permission fix compiled. The Android onboarding UI was
  not redesigned or exercised on a device during this iPhone pass.
- Cloudflare/browser handoff UI and physical-device behavior were not exercised
  live in this pass. This is sign-in qualification, not gameplay qualification.
