# GhostLink — Android Implementation (Stage 1)

> **Tagline:** "Private. Anonymous. Free."  
> **Version:** Version 1.0.0 (Stage 1)

GhostLink is an anonymous, account-free, offline peer-to-peer chat application for Android. Two phones pair using a one-time invitation (QR / code / link) and communicate directly over Wi-Fi Direct. There is no server, no login, no tracking, and no cloud storage.

---

## 1. Tech Stack

- **Language & Framework:** 100% Kotlin, Jetpack Compose (Material 3 with custom GhostLink design tokens), Single-Activity architecture (`MainActivity.kt`).
- **Architecture:** MVVM + StateFlow, Navigation Compose.
- **SDK Level:** `minSdk 26`, `targetSdk 35`.
- **Peer Transport:** Wi-Fi Direct (`WifiP2pManager`) — DNS-SD service discovery, peer group formation, encrypted socket on group owner. Handles Android 13+ (`NEARBY_WIFI_DEVICES`) and legacy (`ACCESS_FINE_LOCATION`) permissions.
- **Cryptography:** Ephemeral X25519 key exchange + ChaCha20-Poly1305 / AES-GCM message encryption bound to the one-time invitation code.
- **QR Code:** Nayuki QR Code generator (MIT, vendored Java source under `app/src/main/java/io/nayuki/qrcodegen`) for generation; fully offline, no Gradle dependency.
- **Storage:** Jetpack DataStore only (alias, settings, preferences). Chat messages are kept in volatile memory only and wiped immediately upon disconnect or exit.
- **Zero Leakage:** No `INTERNET` permission required for local P2P, zero third-party analytics, zero telemetry.

---

## 2. Design System & Palette

- **Ghost Green (Default):**
  - Background: Near-black `#040806` to `#07110C` with subtle radial dark-green glow.
  - Card surface: `#0A130E`, 1dp border `#1E5A3C` with soft outer glow, corner radius 14dp.
  - Primary accent: Neon green `#2BE57A`.
  - Primary button: Dark-green gradient `#14532D` → `#0E3A20`, green border `#1E5A3C`, neon green text `#2BE57A`.
  - Text primary: `#E8F5EC`, secondary/muted: `#7FA690`, status green: `#2BE57A`.
- **Midnight Blue (Screen 12 Alternative):**
  - Background: `#04070D`, card surface `#081220`, border `#1F3F6B`, accent `#3B9CFF`.
- **Typography:**
  - Technical / IDs / Chat bubbles / Time: JetBrains Mono (`GhostMono`).
  - Headings / Body: Clean modern Sans-serif (`GhostSans`).

---

## 3. The 15 Screens (Exact 1:1 Match)

1. **Splash Screen:** Centered glowing ghost logo, `GhostLink` (Ghost white, Link green), `"Private. Anonymous. Free."`, divider, 3 monospace claims (`"No accounts." / "No tracking." / "Just people."`), `"Initializing secure environment..."` progress line.
2. **Home Screen:** Top bar (menu, `"GhostLink"`, subtitle `"Connect • Chat • Stay Private"`, settings gear), alias card with avatar, live alias (`Shadow7F3`), green dot `"Online"`, refresh button, 4 menu cards (`Pair with Someone`, `Your Invitations`, `Nearby Devices`, `Settings`), bottom navigation (`Home`, `Chats`, `Devices`, `Profile`).
3. **Pairing Menu:** Title `"Pair with Someone"`, subtitle `"Choose how you want to connect."`, 4 cards for Scan QR, Enter Code, Open Link, Show Invitation.
4. **Show My Invitation:** Title `"Your Invitation"`, QR code inside `CornerBracketFrame`, invitation code `7K9M-4Q2X` with copy button, share link `ghostlink://invite/7K9M-4Q2X` with copy button, Share Link & Save QR buttons, 10-minute live countdown timer.
5. **Scan QR Code:** Viewfinder with `CornerBracketFrame`, `"Point your camera at the other person's GhostLink QR code."`, bottom card with animated scanning progress and Cancel button.
6. **Enter Invitation Code:** Keyboard circle icon, `"Type the code you received from the other person."`, monospace input (`XXXX-XXXX` with auto-dash), Continue button, single-use disclaimer footer.
7. **Invitation Confirmed:** Green shield-check icon, `"Valid invitation found!"`, `"You're connecting with:"`, card with avatar, `Mystic_2A9`, 4 signal bars, pairing code, connection code, `"Proceed"` button.
8. **Nearby Devices:** Title `"Nearby Devices"`, subtitle `"Select a device to connect."`, refresh icon, animated radar view with sweep and blips, discovered peer list (`Lunar_8D4`, `Nova_3F7`, `Pixel_681`) with signal bars.
9. **Connection Request:** Large ghost avatar, `"Lunar_8D4"`, `"wants to connect with you."`, temporary alias and signal strength card, `"Reject"` and `"Accept"` buttons.
10. **Connected:** Glowing ghost logo, `"Connected!"`, `"You are now securely connected with Lunar_8D4."`, specs card (Alias, Signal, Connection Type: Wi-Fi Direct), `"Start Chat"` button.
11. **Chat (Terminal Style - Green):** Header with avatar, `Lunar_8D4`, `Online`, kebab menu. Chat bubbles in dark green with monospace timestamp prefix (`09:42  Hi! This is GhostLink.`), input bar with `+` attach, `> Type a message...` placeholder, green send arrow.
12. **Chat (Midnight Blue Optional Theme):** Identical terminal structure with Midnight Blue accent `#3B9CFF`.
13. **Settings:** Title `"Settings"`, cards for `"Your Alias"`, `"Connection Preferences"`, `"Privacy & Security"` (with live theme selector for Ghost Green / Midnight Blue), and `"About GhostLink"` (`Version 1.0.0 (Stage 1)`).
14. **Permission Request:** Location pin icon in circle, `"Allow GhostLink to access your location?"`, explanation for Wi-Fi Direct discovery, 3 options: `"Allow while using the app"`, `"Only this time"`, `"Don't allow"`.
15. **Exit / Disconnect:** Ghost logo, `"GhostLink"`, `"Small steps. Big privacy."`, status indicators (`Location: Enabled`, `Wi-Fi Direct: Connected`, `Battery: Optimized`), `"Disconnect"` (session wipe) and `"Back to Home"` buttons.

---

## 4. Project Directory Structure

```
ghost chatapp/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   └── java/com/ghostlink/app/
│   │       ├── MainActivity.kt
│   │       ├── model/Models.kt
│   │       ├── viewmodel/GhostViewModel.kt
│   │       └── ui/
│   │           ├── theme/ (Color.kt, Theme.kt, Type.kt)
│   │           ├── components/ (Components.kt — GhostCard, RadarView, CornerBracketFrame, etc.)
│   │           ├── navigation/ (GhostRoute.kt, GhostNavGraph.kt)
│   │           └── screens/
│   │               ├── splash/ (SplashScreen.kt)
│   │               ├── home/ (HomeScreen.kt)
│   │               ├── pairing/ (PairingMenuScreen.kt, MyInvitationScreen.kt, QrScannerScreen.kt, EnterCodeScreen.kt)
│   │               ├── nearby/ (NearbyDevicesScreen.kt)
│   │               ├── connection/ (InvitationConfirmedScreen.kt, ConnectionRequestScreen.kt, ConnectedScreen.kt, PermissionRequestScreen.kt, ExitDisconnectScreen.kt)
│   │               ├── chat/ (ChatScreen.kt)
│   │               └── settings/ (SettingsScreen.kt)
├── web/                                # Exact 1:1 Live Interactive Web Simulator
│   ├── index.html                      # All 15 screens with screen switcher & frame toggle
│   ├── css/style.css                   # Custom neon green and midnight blue styles
│   └── js/app.js                       # Interactive simulated state & flows
└── README.md
```

---

## 5. Build and Run Instructions

### In Android Studio:
1. Open Android Studio (Hedgehog / Iguana / Koala recommended).
2. Open the `ghost chatapp` root directory.
3. Sync Gradle and run on a physical Android device (Wi-Fi Direct P2P requires real hardware interfaces).

### Interactive Web Simulator:
The project includes a standalone web simulator serving on `http://127.0.0.1:8088/` where you can inspect all 15 screens using the screen selector dropdown or navigate naturally through the app flows.

For the two-phone HTTPS LAN development procedure, mkcert-only certificate setup,
Android development-CA warning, and the unconfirmed real-device checklist, see
[`web/DEV_SETUP.md`](web/DEV_SETUP.md).
