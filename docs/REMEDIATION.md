# GhostLink — Remediation Report

Scope: audit of the existing source, targeted fixes for the connection system, QR
path, discovery, keyboard/insets, release signing and session security; tests and
build evidence. Everything below is either **verified by a command in this repo**
or explicitly marked **unverified**.

---

## 1. Root causes of confirmed defects

### C1 — "QR scans, then connection fails" (Wi-Fi Direct) — CRITICAL
- `GhostViewModel.startConnection()` never called `WifiP2pManager.connect()`. It
  asked `requestConnectionInfo()` first, so `info.groupFormed` was always false and
  every attempt ended with *"Wi-Fi Direct group not formed"*.
- The old `P2PConnectionManager` derived the same session key on both sides
  (`SHA-256(token)`) and then compared the constant string `"GHOST_HANDSHAKE_OK"`.
  That proves nothing: any process that reached the port was accepted, and the
  invitation was never used for authentication.
- The server's receive loop and `receiveRaw()` each wrapped
  `socket.getInputStream()` in a *new* `DataInputStream`, so buffered bytes were
  lost — messages and retries silently stalled.
- No sequence numbers: a captured frame could be replayed inside a session.
- The invitation token fell back to the literal `"GHOSTLINK_P2P_TOKEN"` when no
  invitation existed, so both devices could "authenticate" with a public constant.

**Fix:** new `WifiDirectTransport` (owns discovery, negotiation, listening socket,
session) + `GhostSessionProtocol` (authenticated handshake). Roles come from
`WifiP2pInfo.isGroupOwner`; the address is `groupOwnerAddress` as reported by the
OS (never a hardcoded gateway); the listener binds before the client connects and
the client retries with bounded backoff (0.5–3 s, 6 attempts).

### C2 — Invitation codes were not the codes the user typed — HIGH
`validateAndSubmitCode()` built `Invitation(code = InvitationCode(code))`, whose
`token` defaulted to `UUID.randomUUID()`. The typed code never affected key
material or peer matching. It also searched *discovered device names* for the code
and bound the first fuzzy substring match as `selectedDevice`, so a session could
target an unrelated peer.

**Fix:** an entered code now anchors a new invitation with a fresh 256-bit
`SecureRandom` token; no device is auto-selected from a name match; the pairing
handshake is what authenticates the peer.

### C3 — Invitation lifecycle had no notion of "consumed" — HIGH
`Invitation` had only `expiresAt`. Whether a token had been used was never
tracked, so the documented "one connection attempt" rule could not be enforced —
and any attempt to enforce it naively would burn a valid invitation on a network
failure.

**Fix:** `Invitation.consumedAt` + `isUsable`. `consumedAt` is set **only** in the
`onConnected` callback, which fires after the authenticated handshake succeeds.
Failures, timeouts and disconnects leave it `null`, so retry works; an expired or
consumed invitation is rejected by `usableInvitationToken()` everywhere a transport
needs it. The check is a single state transition on the main thread, so two
concurrent attempts cannot both consume it.

### C4 — Deep link could pre-seed arbitrary input — MEDIUM
`handleDeepLink()` accepted any `ghostlink://` URI, any host, duplicated `code`
parameters and arbitrary length, then wrote the value straight into the code field.

**Fix:** scheme + host allow-list, duplicate-parameter rejection, 64-char cap,
8–12 alphanumeric validation, and refusal while a session is active
(`isAwaitingInvitationInput()`). The link can only *pre-fill* the field; the
confirmation and handshake still run.

### C5 — Repeated discovery — HIGH
- `stopDiscovery()` left the Wi-Fi P2P flow and BLE scan state running; the next
  scan raced still-registered receivers and stale peer lists remained.
- `BluetoothLeManager.onScanFailed` did **not** clear `activeScanCallback`, so every
  later `startScanning()` returned early at *"Scan already running"* — the second
  scan onward found nothing.
- The Nearby screen only showed results at `ACTION_DISCOVERY_FINISHED` (~12 s).

**Fix:** `startDiscovery()` now tears down first (`stopDiscoveryQuietly()`), clears
the peer list, uses one P2P receiver owned by the transport with a generation
counter, publishes merged results on the first device found, and BLE clears its
callback on failure and retries once for `SCAN_FAILED_APPLICATION_REGISTRATION_FAILED`.
`stopDiscovery()` clears `discoveredDevices`/`selectedDevice` so stale peers cannot
be tapped.

### C6 — Wi-Fi Direct absent from the connection menu — MEDIUM
The pairing menu offered only code entry and the QR screen; transport selection
happened implicitly on the Nearby screen with no availability state.

**Fix:** `PairingMenuScreen` now has a **Local transports** section with Wi-Fi
Direct and Bluetooth rows, each showing a precise disabled-state reason
(unsupported hardware / permission / Wi-Fi off / no invitation). Also surfaced on
the Nearby screen, which shows the active transport and a per-device badge.

### C7 — Keyboard overlaps the composer and code field — HIGH
`ChatScreen` had no `imePadding()`; the composer sat under the keyboard.
`EnterCodeScreen` used `Spacer(weight(1f))` inside a non-scrollable column with no
IME inset handling, so the field could be pushed off-screen on short devices.

**Fix:** `Scaffold(contentWindowInsets = safeDrawing.only(Top + Horizontal))` plus
`.navigationBarsPadding().imePadding()` on the body exactly once (no double
padding), the code screen scrolls, and the chat list re-scrolls when the IME opens.
The invitation screen now sizes the QR between 250–300 dp with `aspectRatio(1f)`.

---

## 2. Files changed

| File | Change |
|---|---|
| `networking/GhostSessionProtocol.kt` | **new** — SAS handshake, HKDF key, AES-256-GCM frames with sequence binding |
| `networking/WifiDirectTransport.kt` | **new** — P2P discovery, group negotiation, listener, client retry, session |
| `networking/P2PConnectionManager.kt` | replaced by a documented marker; insecure duplicate state machine removed |
| `networking/GhostMessageCodec.kt` | added record encode/decode helpers |
| `discovery/BluetoothLeManager.kt` | clear scan callback on failure, one bounded retry, close flag |
| `viewmodel/GhostViewModel.kt` | Wi-Fi Direct wiring, capability model, invitation lifecycle, discovery restart, `SecureRandom` invite codes |
| `model/Models.kt` | `Invitation.consumedAt`, `isUsable`, lifetime constant |
| `MainActivity.kt` | hardened deep-link handling |
| `ui/screens/pairing/PairingMenuScreen.kt` | Local transports section |
| `ui/screens/nearby/NearbyDevicesScreen.kt` | transport status line, per-device badge, empty-state hint |
| `ui/components/TransportChips.kt` | **new** — explained enabled/disabled transport row |
| `ui/screens/chat/ChatScreen.kt` | IME + navigation-bar insets, scroll on IME |
| `ui/screens/pairing/EnterCodeScreen.kt` | scrollable, IME + nav-bar insets |
| `ui/screens/pairing/MyInvitationScreen.kt` | insets, responsive 250–300 dp QR |
| `ui/screens/pairing/QrScannerScreen.kt` | nav-bar inset |
| `ui/navigation/GhostNavGraph.kt` | transport props, invitation bootstrap, rescan |
| `app/build.gradle.kts` | dedicated release signing config; org.json for JVM tests |
| `gradle/libs.versions.toml` | CameraX + ML Kit (scanner, added previously) |
| tests | `GhostSessionProtocolTest` (new), `P2PCryptoTest` (rewritten), `QrPayloadTest` |

---

## 3. Commands and results

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # default JDK 25 is rejected by AGP 8.5.2

./gradlew :app:testReleaseUnitTest      # 44 tests, 0 failures
./gradlew :app:assembleRelease          # BUILD SUCCESSFUL, signed with release identity
./gradlew :app:assembleDebug            # BUILD SUCCESSFUL
./gradlew :app:lintRelease              # 0 errors, 36 warnings (all pre-existing)
```

Test coverage added (all JVM, no device):

- handshake succeeds with matching invitations; SAS matches on both sides
- different invitation cannot complete the handshake
- session keys differ per handshake even with the same invitation
- tampered tag rejected; replayed frame rejected; out-of-order frame rejected
- oversized length rejected before allocation; truncated frame errors; clean EOF → null
- AES-GCM primitive: round trip, tamper, wrong key, wrong AAD

**Bug found by these tests:** the first version of the frame `open()` read the
sequence from the *head* of the plaintext while `seal()` appends it at the *tail*,
so every valid frame was rejected. Fixed and covered by the round-trip test. A
second real issue found on the way: `seal()` originally used a random IV while
reusing one `Cipher` instance; it now uses a per-sender random prefix + monotonic
counter and a fresh `Cipher` per frame.

---

## 4. Security findings

### Fixed
| ID | Sev | Finding | Evidence / remediation |
|---|---|
| S1 | Critical | Unauthenticated "handshake" (constant string; key never used for auth) | `GhostSessionProtocol`: both sides contribute 256 random bits, HKDF with invitation as salt, HMAC key confirmation, first frame authenticated before any state is trusted |
| S2 | Critical | Release APK signed with the **debug** certificate | `app/build.gradle.kts` now requires `keystore.properties`/env vars; verified release cert CN below |
| S3 | High | Replay possible (no sequence) | IV counter + encrypted sequence both checked; tests cover replay and reorder |
| S4 | High | Invitation token fallback to a public constant | Removed; transports refuse to start without a usable invitation |
| S5 | High | Typed invitation code ignored for key material | Fixed (C2) |
| S6 | Medium | Deep link input not validated | Fixed (C4) |
| S7 | Medium | Invite code generated with non-crypto `Random` | `SecureRandom` |
| S8 | Low | Vendor-supplied RSSI/alias treated as verified | Wi-Fi Direct sessions report `isVerified = true` only after key confirmation |

### Open / residual
| ID | Sev | Finding | Status |
|---|---|
| R1 | Medium | Screenshot protection defaults to **on** and is applied via `FLAG_SECURE`, which also blocks the scanner preview in some OEM implementations | Not changed (privacy-by-default is intentional); flagged for device testing |
| R2 | Medium | Custom URI scheme can be claimed by any installed app (`ghostlink://`) | Residual by design; mitigated because the link can only pre-fill a field and the token must still survive the authenticated handshake |
| R3 | Medium | Token is a symmetric pre-shared secret: no forward secrecy beyond invitation lifetime, no long-term identity | Documented in `GhostSessionProtocol`; X25519/Noise is the follow-up |
| R4 | Low | DataTransport + Firebase components are present transitively (via ML Kit), with `FirebaseInitProvider`/`TransportBackendDiscovery` in the merged manifest | App code never references them (`grep` clean); presence ≠ transmission. Not exercised at runtime — see §6 |
| R5 | Low | `ACCESS_FINE_LOCATION` requested for API ≤ 32 (required by WifiP2p) | Required by the platform; declared with a justification comment |

---

## 5. Performance

No baseline timings were captured before the changes, so no before/after numbers
are claimed. Structural improvements that affect measured stages:

- discovery results render on the **first** peer (previously only at the ~12 s
  `DISCOVERY_FINISHED` broadcast);
- rescan no longer waits for a previous run to unwind;
- Wi-Fi group formation is bounded at 45 s with an explicit timeout error instead
  of hanging, and the client retries connect with 0.5–3 s backoff;
- no new blocking work on the main thread; QR generation and scanning remain off
  the main thread.

Instrumented stage timings (cold start, first peer, group formation, handshake) are
**not** available without a device.

---

## 6. Remaining issues / not verified

- **No emulator or system image** is installed, so no UI was rendered and no
  instrumentation test ran. Keyboard/inset behaviour, rotation, cutouts and API 35
  edge-to-edge are implemented but **unverified on-device**.
- **Wi-Fi Direct, BLE and Bluetooth Classic interoperability require two physical
  phones.** Everything in those paths is unit-tested where possible (protocol,
  frames, parsing) and compile/lint-clean, but real group formation, OEM
  variations and the 2.4 GHz scan are untested.
- `WifiDirectManager` (the discovery-only wrapper) is still present but no longer
  drives connections; removing it entirely is a follow-up cleanup.
- The Group Owner role is requested with `groupOwnerIntent = 0`, but if the OS picks
  the other side the flow still works because the role is read from `WifiP2pInfo`.

---

## 7. Build artifacts

| Artifact | Path | Size | SHA-256 |
|---|---|
| Release (dedicated key) | `/home/anonymous/Downloads/GhostLinkBuild/GhostLink.apk` | 29,105,367 B (27.8 MB) | `1ce50eaeec1be6f9095f357bec6d25725b2d9adcfec4cf1d7d7392fe7c43b1ae` |
| Debug | `/home/anonymous/Downloads/GhostLinkBuild/GhostLink-debug.apk` | 40,878,613 B (39.0 MB) | — |

Signing identity: `CN=GhostLink Release, OU=Mobile, O=GhostLink, C=US`
(certificate SHA-256 `f7b4fb0bd47dfb1610d6aa44d059c1d2cd3aaf216c221af09fc9b37f2e2c0a7e`,
RSA 4096, 10000 days),
keystore at `~/.ghostlink-release/ghostlink-release.jks` — **outside** the repo;
credentials in git-ignored `keystore.properties`.

---

## 8. Two-device test checklist

1. Install `GhostLink.apk` on both phones; grant Nearby/Wi-Fi, Bluetooth and Camera.
2. **A**: *Pair with Someone → Show My Invitation Code*. Confirm QR renders on white,
   code and countdown are visible, in both portrait and landscape.
3. **B**: *Pair with Someone → Scan QR Code* → scan A's QR. Expect: connecting →
   **Connected**, and both phones show the same SAS string if surfaced.
4. Send a message B→A and A→B; confirm delivery, then rotate the device mid-chat.
5. Open the keyboard on the chat screen: composer must sit directly above it; send
   must work; the message list must stay scrollable.
6. Turn Wi-Fi off on one phone → menu must show *"Wi-Fi is turned off"* and disable
   the Wi-Fi Direct row rather than failing silently.
7. Return to Nearby and rescan three times: devices must reappear every time
   without restarting the app.
8. Re-scan the **same** QR after a successful connection: must be rejected as
   already used. Cancel a connection mid-negotiation and retry: must still work.
9. Kill Wi-Fi mid-session: both sides must show a disconnected/retryable state, and
   a retry with a still-valid invitation must succeed.
10. Deny Camera, then grant it from Settings: scanner must recover without restart.

---

# Part 2 — SAS verification, transport unification, test layers, re-scored audit

## 9. Test-count reconciliation (the 44 vs 52 discrepancy)

The earlier report's categories did not sum to its total. Verified inventory from
`/tmp/ghostlink-build/app/test-results/testReleaseUnitTest/*.xml` **before** this round:

| Class | Tests | Report claimed |
|---|---:|---|
| `GhostSessionProtocolTest` | 13 | — |
| `P2PCryptoTest` | 5 | — |
| `GhostMessageCodecTest` | 4 | — |
| `GhostProtocolTest` | 4 | — |
| `QrPayloadTest` | 10 | 10 ✓ |
| `InvitationCodeTest` | 3 | 2 ✗ |
| `ChatModelTest` | 5 | 2 ✗ |
| **Total** | **44** | 52 ✗ |

The error was in the "38 cryptography/framing" figure: those four classes total
**26**, not 38. 26 + 10 + 3 + 5 = 44. The previously quoted 38.85% inherited that
miscount and is superseded by §14.

## 10. SAS defect — fixed (was the top blocker)

**Root cause:** `WifiDirectTransport.activateSession()` computed the SAS and then
called `onConnected()`. The listeners had no SAS parameter, so
`GhostViewModel`'s `val sas = _uiState.value.sessionSas` always read `null`, and
`sessionSas` was never assigned. The safety code existed only inside the transport.

**Fix — a real verification phase, fail-closed:**

1. `GhostTransportListeners.onPeerAuthenticated(sessionId, sasHex, peerLabel)` and
   `onVerificationFailed(sessionId, reason)` added.
2. `GhostTransport.confirmVerification(sessionId)` / `rejectVerification(sessionId)`.
3. `WifiDirectTransport` **does not start its reader/writer threads** until
   confirmation, so no plaintext can flow during verification; `sendMessage()`
   additionally requires `session.isVerified`.
4. New `SasVerificationGate` (pure, testable) enforces: only the current attempt can
   be resolved; stale ids are refused; a new attempt supersedes the old one; a
   90 s timeout fails closed; double-confirm cannot open two sessions.
5. `GhostViewModel` now publishes `sessionSas` + `pendingSessionId`, and
   `confirmSessionVerification()` / `rejectSessionVerification()` guard against
   stale ids.
6. `ConnectionProgressScreen` renders a safety-code panel (grouped hex, accessible
   description) with explicit **Codes match** / **Codes differ** actions. Cancel
   during verification rejects the attempt.

Invitation consumption now uses the pure `InvitationLifecycle`
(`canStart`, `onAuthenticationSucceeded`, `onAttemptFailed`) and only happens on
the verified `onConnected` path.

## 11. Bluetooth Classic — disabled, not silently insecure

Inspection confirmed the Bluetooth data path still does `GHLK/1 HELLO/ACK` and then
`GhostMessageCodec.writeMessage(output, text)` — **cleartext**, unauthenticated.
Migrating it faithfully (and proving interop) could not be verified here, so per
the brief the insecure fallback is **disabled**:

- `startConnection()` refuses `ConnectionMethod.Bluetooth` with
  `BLUETOOTH_DATA_DISABLED_REASON` and refuses `BLE` as discovery-only.
- The pairing menu no longer advertises Bluetooth as an encrypted transport.
- The Bluetooth listener's `onPeerAuthenticated` closes any inbound session rather
  than treating it as connected. BLE/Bluetooth **discovery** is untouched.

Residual: the class still contains the legacy code. It is unreachable from the UI
but not deleted; deleting it is the safe follow-up (`docs/REMEDIATION.md` R6).

## 12. Result of the QR → connect state machine repair

A typed or scanned invitation sets **no peer**; the confirmation screen previously
navigated to "Connecting" anyway and failed silently. It now routes to *Nearby
Devices* to pick the peer that holds the invitation.

## 13. New test layers

| Layer | State |
|---|---|
| Unit (JVM) | **68 tests, 0 failures** (was 44) |
| Compose instrumentation | 10 tests added in `app/src/androidTest`, **compiles**, `assembleDebugAndroidTest` BUILD SUCCESSFUL — **not executed** (no emulator/device) |
| Performance probes | `PerfTrace` stage timer wired into the real pipeline, debug builds only — **no measurements taken yet** (needs a device; sample-based, not Macrobenchmark) |
| Real-device | **not executed** — no hardware |

New unit tests: `InvitationLifecycleTest` (12: cases 1–9 incl. retry, concurrency,
scan-does-not-connect), `SasVerificationGateTest` (10: stale/mismatch/timeout/cancel/double
confirm), plus 2 protocol tests proving the SAS is per-session and not derivable
without the invitation.

## 14. Re-scored functionality (verified inventory: 68 unit, 10 instrumentation not run)

Formula: `category = (passed + 0.5×partial) / (passed + partial + failed + untested)`,
weighted Connectivity 25 · QR 20 · Discovery 15 · Security 20 · UI 12 · Performance 8.

| Category | Score | Basis |
|---|---:|---|
| Connectivity | **35%** | +SAS lifecycle 10 tests, invitation lifecycle 12; still zero transport execution |
| QR pairing | **63%** | payload 10 + lifecycle 12; scan→connect path unexecuted |
| Discovery | **25%** | unchanged — no automated test reaches Android discovery |
| Security | **76%** | gate + lifecycle + SAS-derivation tests; BT path now closed; SAS display still unrendered on a device |
| UI | **22%** | 10 instrumentation tests written and compiling, 0 executed; insets code-inspected |
| Performance | **8%** | instrumentation added, zero measurements |

**Overall ≈ 44.4%** (item-by-item sum: 8.75 + 12.6 + 3.75 + 15.2 + 2.64 + 0.64).
Up from 43% before, mostly because security and pairing are now genuinely tested;
this is a *small* increase because the untested hardware/UI weight did not move.

## 15. Final artifacts

| Artifact | Size | SHA-256 |
|---|---:|---|
| `GhostLink.apk` (release) | 29,105,367 B | `173616a6bbab7c25bd39486ac0cbaac2dcaca2d278212eb5d3bd55544a758a9d` |
| Debug / androidTest APKs | built | — |

Signing: `CN=GhostLink Release, OU=Mobile, O=GhostLink, L=Unknown, ST=Unknown, C=US`.
Lint: **0 errors, 41 warnings** (all "newer dependency available"; the one source
warning — `ModifierParameter` in `TransportRow` — was fixed).

## 16. Residual risks (unchanged or new)

| ID | Sev | Finding |
|---|---|---|
| R6 | Medium | Legacy Bluetooth Classic code still present but unreachable |
| R7 | Medium | SAS verification UI compiles but has never been rendered on a device |
| R8 | Medium | Symmetric PSK token: no forward secrecy beyond invitation lifetime |
| R9 | Low | `ghostlink://` scheme claimable; mitigated (pre-fill only) |
| R10 | Low | Firebase/DataTransport transitive via ML Kit, unexercised |

## 17. Two-device checklist (additions in **bold**)

1–10 as before, plus:
11. **After scanning, both phones must show the same 16-char safety code; tap
    "Codes match" on both → Ready.**
12. **Tap "Codes differ" on one phone → both must fail closed and return to a
    retryable state; the socket must close.**
13. **Wait 90 s without confirming → both must time out with a clear message.**
14. **Select a Bluetooth device → must show the disabled-data explanation, not
    connect.**
