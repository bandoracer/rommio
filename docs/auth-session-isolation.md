# Authentication session isolation

Status: implemented and regression-tested primitives and request wiring; interactive provider/device qualification remains open under R04/R17.

## Ownership

Native cookies belong to an exact `(profile ID, configured base URL, cookie generation)`. Two profiles on one host do not share a jar. A deployment path or origin change gets a different jar. Existing unowned global cookies are not automatically assigned to any profile; cookie-based profiles may need to sign in again after this upgrade. Password/service credentials retain their existing profile ownership.

Every native request captures its cookie generation. Responses and redirects may update only that generation, within the configured credential scope. Logout rotates the generation before clearing it. A late response or a browser opened before logout cannot populate the replacement jar. Deleting an inactive profile clears that profile's cookies too, without clearing the active profile.

Logout clears origin credentials and this profile's cookies. If its server-access layer used cookies, server access also becomes unverified and requires sign-in again. Service-token access can remain ready on ordinary origin logout; explicit server-access clearing also removes service/device/origin credentials and invalidates in-flight refresh. The app does not claim to revoke an upstream identity provider's system-browser session.

## Verified account ownership

Both authentication owners now record the configured server URL and the numeric user ID returned by a successful, in-scope `/api/users/me` response. Usernames and profile labels do not establish identity. Password-grant tokens and explicit Basic credentials are verified using the candidate credentials before replacing the stored credentials. A different account is rejected while the existing credentials and ownership remain intact. Refresh of a bound profile also verifies the candidate token before installing it.

The guard is one persistent record per profile in Android encrypted preferences or the iOS secret store. Its key hashes the exact profile ID rather than applying filename sanitization. It is retained by logout, credential clearing, and profile deletion: those actions must not make a reused profile ID eligible for reassignment. Missing records are established by the first verified login/validation; corrupt records fail closed. The guard is written before installing credentials, under the existing authentication generation fence. A crash between those writes may require signing in again as the same owner; it cannot authorize another account.

Full request decoration rejects an edited server URL that disagrees with the established owner. Explicit session validation also compares the current user against the guard. If validation detects a changed browser/server account, it retires the usable origin session and cookies while preserving ownership. A different-account password attempt reports an actionable error instead of silently changing the existing profile's owner.

This is an authentication boundary, not migration of existing saves. It does not assign legacy files, existing downloads, or old queued work to the first account that signs in. R05/R06 must snapshot verified identity into those records and preserve ambiguous ownership through recoverable migration. The UI for adding another account at the same server, browser-transition request parking, and the complete profile/database mutation race audit remain open. In particular, validation detects changed cookie ownership, but this batch does not prove that no other request can run during browser handoff. The future namespace record remains the authority for progress ownership independently of credential storage.

## Android

`ProfileCookies` uses a named AndroidX WebView profile shared by this profile's embedded browser and native request adapter. The name is an opaque UUID stored in encrypted preferences. API calls, downloads, device registration, login/refresh, and native access tests use the same request tags and cookie interceptor. The interceptor recomputes cookies on each redirect hop and processes `Set-Cookie` before the next hop.

The global `CookieManager.getInstance()` no longer participates in authentication. Browser widgets are recreated when the profile's cookie-store name changes and are stopped/destroyed on release. Finishing an old sign-in attempt checks the captured generation. Callback scope checks include scheme, host, port, and deployment path, not a string prefix.

AndroidX WebKit 1.12.1 supplies the [profile-store API](https://developer.android.com/reference/androidx/webkit/ProfileStore). Runtime feature checks remain necessary: an old Android System WebView without `MULTI_PROFILE` receives an actionable sign-in error; native password/service-token paths do not require that feature. There is no fallback to a shared authenticated jar.

## iOS

Native URL requests disable automatic shared-cookie handling. `ProfileCookies` owns cookie attributes and expiry, stores persistent cookies through `SecretStore` (Keychain in the live app), and matches secure/domain/path requirements in addition to Rommio's credential scope. Cookies without an expiry remain process-local. Discovery probes also disable automatic cookies.

Prepared requests carry cookie ownership as local URL request metadata, not an HTTP header. API/download/auth response handling and the redirect delegate pass the original prepared request back to that owner. External responses cannot update the jar. Each retry is decorated again, while stale response generations are ignored.

Interactive sign-in now presents a private `WKWebView` sheet seeded from the selected profile. Done becomes available on the configured RomM origin/path; the app explicitly transfers that sheet's cookies and then performs native access/user validation. The sheet's cookie store is never shared with another profile. Cancel and dismissal terminate the pending operation. The anchor provider is retained for the authenticator's lifetime; the previous weak-only ownership could lose the newly created provider before presentation.

The previous system-browser authentication session did not provide a native cookie-transfer mechanism for this server flow. Providers that disallow embedded browser authentication still require qualification and an explicitly supported authentication route; the new sheet is not evidence that every OIDC provider works. Real provider flows, cancellation/dismissal, device backgrounding, and restart remain R04/R17 gates.

Keychain updates now use `SecItemUpdate` for an existing value, avoiding a remove-then-add window when replacing tokens or cookie jars.

## User-configured HTTP servers

The app's discovery and documented emulator/server setup support explicitly configured HTTP endpoints. Android's manifest previously rejected these before any request reached the server. The manifest now permits cleartext transport. iOS's app Info.plist also enables this transport and includes a local-network usage explanation; its checked-in project and project generator both reference that plist.

The iOS exception follows Apple's [ATS configuration semantics](https://developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity/nsallowsarbitraryloads) because arbitrary user-selected server domains cannot be listed as static per-domain exceptions. The local-network explanation follows [TN3179](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy). HTTPS certificate validation remains in place; credentials still cannot follow a redirect to another origin/path, including an HTTPS-to-HTTP downgrade. HTTP transport itself is unencrypted, so endpoint selection determines whether that connection has TLS.

## Evidence and remaining checks

- Android: `ProfileCookiesTest` exercises two profiles on one host, deployment/scheme boundaries, logout plus late responses, unrelated-profile preservation, real WebView profile attachment, owner recreation, and a real HTTP redirect that both issues and consumes a cookie. A redirect to another port cannot send or poison the profile cookie jar.
- Swift: five `ProfileCookiesTests` cover ownership, expiry/deletion, persistent-versus-session restart behavior, stale browser completion, foreign domains, and a real HTTP redirect through `RommAPIClient` with a deliberately populated ambient cookie store. Two additional controller tests cover logout/deletion across profiles and rejection of unowned global cookies.
- Account ownership: six `VerifiedAccountTest` Android device cases exercise real HTTP responses, encrypted preferences, and Room; five added Swift controller cases cover equivalent login/validation/refresh boundaries, and two file-backed store cases cover durable ownership, key isolation, rebinding rejection, and corrupt records. These tests do not qualify the remaining browser-transition/profile-mutation races.
- Both Android flavor unit/lint/build checks pass with 55 unit cases each. The full Android instrumentation XML reports 21 cases, zero failures/errors, and two live-environment skips; 18 cases cover cookies, ownership, and the progress store, plus one existing migration case. Swift package tests pass with 110 cases and two pre-existing live-environment skips. The iOS simulator app builds and passes strict signature verification.
- This does not close R04: the remaining auth mutation race audit, real provider/device qualification, and durable sync scheduling/parking integration are still required. Account/cookie guards are active now; the new progress store remains inactive pending R05–R10 cutover.
