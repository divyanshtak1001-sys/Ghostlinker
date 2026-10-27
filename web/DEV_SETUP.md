# GhostLink web LAN development

## Before starting

Install `mkcert` and run `mkcert -install` on the Windows development PC.
Do not use the bundled certificate files for phone testing: regenerate them for
the physical adapter IP printed by the server.

From `web/`, use the actual Wi-Fi adapter address. For the current test address:

```powershell
mkcert -cert-file cert.pem -key-file key.pem 10.10.49.9 localhost 127.0.0.1
```

Regenerate these files if the adapter address changes. The server refuses to start
when its certificate does not cover the selected IP; `cert.pem` and `key.pem` are
ignored by source control. The browser must trust the mkcert CA without a TLS
warning; accepting a warning is not a substitute for certificate trust.

Start the relay:

```powershell
npm start
```

The server lists physical and excluded virtual interfaces. Select the Wi-Fi or
phone-hotspot adapter. It binds only to that selected address and prints the
HTTPS URL and a QR code.

In an Administrator PowerShell, permit the selected HTTPS port:

```powershell
New-NetFirewallRule -DisplayName "GhostLink HTTPS" -Direction Inbound -Protocol TCP -LocalPort 8443 -Action Allow
```

## Trusting mkcert on Android (development only)

1. On Windows, run `mkcert -CAROOT` and copy `rootCA.pem` from that folder to
   the Android device by USB or another trusted local transfer.
2. On Android, open **Settings > Security & privacy > More security settings >
   Encryption & credentials > Install a certificate > CA certificate**. The
   exact labels vary by Android version and vendor.
3. Select `rootCA.pem` and confirm the device warning. Set a screen lock first
   if Android requires it.
4. Open the printed `https://<selected-IP>:8443` URL on both phones. Android
   should show the secure connection without a certificate warning.
5. When testing ends, remove the installed user CA certificate from the same
   credentials settings page.

Never distribute the mkcert root CA, commit it, send it with the app, or use it
outside your own development devices. Anyone with that CA can impersonate sites
trusted by devices where it is installed.

## Two-device web checklist (pending real-phone confirmation)

- [ ] Both phones join the same hotspot/Wi-Fi and can open the printed HTTPS URL.
- [ ] Each phone trusts the development CA only for this test; no warning appears.
- [ ] Phone A creates an invitation; Phone B scans its QR; encrypted chat works.
- [ ] Phone A creates an invitation; Phone B enters the typed code; both compare
      identical safety numbers before chat opens.
- [ ] A copied public invite link opens the app but requires the code separately,
      warns that it is less private, and requires safety-number verification.
- [ ] The disappearing timer is presented as a request to the peer and local
      removal occurs; do not treat it as a delivery or deletion guarantee.
- [ ] Settings shows the RAM-only claim only after its startup verification.
- [ ] Disconnect/panic wipe ends chat and a fresh session has fresh safety data.
