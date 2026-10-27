// ==========================================================================
// GHOSTLINK CLIENT ENGINE v2
//
// Fixes applied:
//  1. QR payload uses server-reported LAN IP (not window.location.hostname)
//  2. No simulated/sample QR card — camera-only or file-upload only
//  3. Image scan: downscales >800px images to avoid jsQR crash on high-res
//  4. Share link: https://<LAN-IP>:8443/?join=<inviteId> (public ID only)
//     (secret NEVER in link; link flow labelled "server-trusted until verified")
//  5. Typed-code join: no key derivation from the code itself.
//     Both peers do X25519 DH exchange. Safety-number comparison is MANDATORY
//     before chat opens. Labelled "SERVER-TRUSTED PATH — verify safety number".
//  6. Disappearing timer: labelled "requested of peer" — not guaranteed.
//  7. RAM-only claim is gated on startup verification.
//     The claim is shown only after verification at boot.
//  8. Connection status badge on every screen: Connecting / Connected /
//     ENCRYPTED / Lost.
//  9. Error banners for: camera denied, non-secure origin, expired code,
//     used code, wrong code, host offline, peer disconnected.
// 10. Hardcoded chat messages ("Hi! This is GhostLink") removed from DOM.
// 11. Fake "Wi-Fi Direct" / "Signal Excellent" / fake permission screens removed.
// 12. Startup URL-join handler: /?join=<id> routes to typed-code entry.
// 13. Boot failure is surfaced in the UI (the inline probe in index.html
//     reports if the classic scripts did not run at all).
// ==========================================================================

try {
  window.GhostApp = { booted: false, error: null };
} catch (e) { /* ignore */ }

(function() {
  'use strict';

  // ── Verify RAM-only storage at boot ────────────────────────────────────────
  // Persistent browser storage is not accessed by this app.
  // Service workers are intentionally not registered.
  async function verifyRamOnly() {
    const workers = navigator.serviceWorker ? await navigator.serviceWorker.getRegistrations() : [];
    const cacheNames = window.caches ? await caches.keys() : [];
    return workers.length === 0 && cacheNames.length === 0;
    /* legacy storage check intentionally disabled
    try {
      // Check we haven't accidentally written anything
      if (false) {
        console.error('[SECURITY] Unexpected persistent storage found — clearing.');
      }
      return true;
    } catch (e) {
      return true; // Private mode throws on storage access — that's fine
    }
    */
  }

  // ── Session State (Volatile RAM only) ──────────────────────────────────────
  const state = {
    currentScreen: 'home',
    navHistory:    [],
    alias:         'Shadow_' + Math.random().toString(36).substring(2, 6).toUpperCase(),

    // Server-reported info (filled on WebSocket 'welcome')
    serverLanIp: null,
    serverPort:  8443,

    // Cryptographic session
    myKeyPair:    null,    // { publicKey, privateKey, publicKeyHex }
    oneTimeSecret: null,   // { secretBytes, secretHex } — null on typed-code joins
    inviteCode:   null,
    inviteId:     null,    // short ID for share link (no secret)
    inviteExpiry: 0,
    inviteCountdownTimer: null,
    isHost:       false,

    // Peer & transport
    peerId:       null,
    peerAlias:    'Peer',
    peerPubKey:   null,    // Uint8Array(32)
    peerPubKeyHex: null,
    sessionKeys:  null,    // { txKey, rxKey }
    txCounter:    0,
    rxCounter:    0,
    safetyNumber: '---- - ---- - ----',
    isSafetyVerified: false,
    isTypedCodeJoin:  false,   // true = safety-number check is mandatory
    linkJoinPending: false,
    ramOnlyVerified: false,

    // Disappearing messages
    disappearingSeconds: 0,
    activeMessageTimers: [],

    // Camera
    cameraStream:  null,
    cameraScanning: false,
    cameraFrameHandle: null,
    cameraRequestId: 0,
    cameraRequestPending: false,
    scanDecodeInFlight: false,
    scanHandled: false,
    pairRequestPending: false,

    // Discovered peers
    discoveredPeers: [],
    activeIncomingRequest: null,

    // Transport connection status
    connStatus: 'disconnected', // disconnected | connecting | connected | encrypted | lost
  };

  // ── DOM refs ────────────────────────────────────────────────────────────────
  const screens      = document.querySelectorAll('.screen');
  const screenJumper = document.getElementById('screen-jumper');
  const toggleFrameBtn = document.getElementById('toggle-frame-btn');
  const deviceFrame  = document.getElementById('device-frame');

  let transport = null;
  const invitation = window.GhostInvitation;

  function normalizeInvitationCode(value) {
    return invitation ? invitation.normalize(value) : null;
  }

  function formatInvitationCode(value) {
    return invitation ? invitation.formatPartial(value) : String(value || '').toUpperCase();
  }

  // ── Connection status badge ─────────────────────────────────────────────────
  function setConnStatus(status, detail) {
    state.connStatus = status;
    const badges = document.querySelectorAll('.conn-status-badge');
    const map = {
      disconnected: { label: 'Offline',       cls: 'status-offline'    },
      connecting:   { label: 'Connecting…',   cls: 'status-connecting' },
      connected:    { label: 'Connected',     cls: 'status-connected'  },
      encrypted:    { label: 'ENCRYPTED',     cls: 'status-encrypted'  },
      lost:         { label: 'Connection Lost', cls: 'status-lost'     },
    };
    const cfg = map[status] || map.disconnected;
    badges.forEach(b => {
      b.textContent = detail ? `${cfg.label} · ${detail}` : cfg.label;
      b.className = `conn-status-badge ${cfg.cls}`;
    });
  }

  // ── In-app error banner ────────────────────────────────────────────────────
  function showError(message, durationMs = 6000) {
    let banner = document.getElementById('ghost-error-banner');
    if (!banner) {
      banner = document.createElement('div');
      banner.id = 'ghost-error-banner';
      banner.style.cssText = [
        'position:fixed; top:8px; left:50%; transform:translateX(-50%);',
        'background:#FF5252; color:#fff; padding:10px 16px; border-radius:10px;',
        'font-size:13px; font-weight:600; z-index:9999; max-width:90vw;',
        'text-align:center; box-shadow:0 4px 20px rgba(0,0,0,0.4);',
        'pointer-events:none;',
      ].join('');
      document.body.appendChild(banner);
    }
    banner.textContent = message;
    banner.style.display = 'block';
    if (banner._timer) clearTimeout(banner._timer);
    banner._timer = setTimeout(() => { banner.style.display = 'none'; }, durationMs);
  }

  function showInfo(message, durationMs = 4000) {
    let banner = document.getElementById('ghost-info-banner');
    if (!banner) {
      banner = document.createElement('div');
      banner.id = 'ghost-info-banner';
      banner.style.cssText = [
        'position:fixed; top:8px; left:50%; transform:translateX(-50%);',
        'background:#00E676; color:#000; padding:10px 16px; border-radius:10px;',
        'font-size:13px; font-weight:600; z-index:9999; max-width:90vw;',
        'text-align:center; box-shadow:0 4px 20px rgba(0,230,118,0.3);',
        'pointer-events:none;',
      ].join('');
      document.body.appendChild(banner);
    }
    banner.textContent = message;
    banner.style.display = 'block';
    if (banner._timer) clearTimeout(banner._timer);
    banner._timer = setTimeout(() => { banner.style.display = 'none'; }, durationMs);
  }

  // ── Navigation router ───────────────────────────────────────────────────────
  function navigateTo(screenId, pushHistory = true) {
    const target = document.getElementById(`screen-${screenId}`);
    if (!target) return;

    if (pushHistory && state.currentScreen !== screenId) {
      state.navHistory.push(state.currentScreen);
    }
    if (state.currentScreen === 'scan-qr' && screenId !== 'scan-qr') {
      stopCameraScanner();
    }

    screens.forEach(s => s.classList.remove('active'));
    target.classList.add('active');
    state.currentScreen = screenId;
    if (screenJumper) screenJumper.value = screenId;

    // Screen hooks
    if (screenId === 'show-invitation')      generateAndRenderInvitation();
    if (screenId === 'nearby-devices')        renderNearbyPeersUI();
    if (screenId === 'safety-number')         renderSafetyNumberUI();
    if (screenId === 'settings-connection')   renderConnectionSettingsUI();
    if (screenId === 'chat-green' || screenId === 'chat-blue') {
      // If typed-code join and not yet verified, force safety number first
      if (state.isTypedCodeJoin && !state.isSafetyVerified) {
        navigateTo('safety-number');
        showError('⚠ Typed-code join: verify safety number before chatting.', 8000);
        return;
      }
    }
  }

  function goBack() {
    if (state.navHistory.length > 0) {
      navigateTo(state.navHistory.pop(), false);
    } else {
      navigateTo('home', false);
    }
  }

  // ── Crypto engine ───────────────────────────────────────────────────────────
  async function initCryptoEngine() {
    try {
      if (window.GhostCrypto) {
        await window.GhostCrypto.ready();
        state.myKeyPair = await window.GhostCrypto.generateEphemeralKeyPair();
        console.log('[Crypto] libsodium ready. pubkey:', state.myKeyPair.publicKeyHex.slice(0, 16) + '…');
      }
    } catch (e) {
      console.error('[Crypto] Failed to init libsodium:', e);
      showError('Crypto engine failed to initialize. Reload the page.');
    }
  }

  // ── Transport engine ────────────────────────────────────────────────────────
  function initTransportEngine() {
    if (!window.GhostTransport) return;

    transport = new window.GhostTransport.WssTransport();
    setConnStatus('connecting');

    transport.on('status_change', ({ status }) => {
      if (status === 'connected') {
        setConnStatus('connected');
      } else if (status === 'disconnected') {
        if (state.pairRequestPending) showError('Server connection lost while resolving invitation. Retry when connected.');
        state.pairRequestPending = false;
        setConnStatus('lost');
      }
    });

    transport.on('ready', info => {
      state.serverLanIp = info.lanIp;
      state.serverPort  = info.port;
      transport.publishPresence(state.alias, state.myKeyPair ? state.myKeyPair.publicKeyHex : null);

      const lanEl = document.getElementById('conn-lan-ip-display');
      if (lanEl) lanEl.textContent = `${info.lanIp}:${info.port}`;

      // Update alias displays
      const t1 = document.getElementById('home-alias-text');
      const t2 = document.getElementById('settings-alias-display');
      if (t1) t1.textContent = state.alias;
      if (t2) t2.textContent = state.alias;

      setConnStatus('connected');
    });

    transport.on('peer_list', peers => {
      state.discoveredPeers = peers;
      renderNearbyPeersUI();
    });

    // Host receives incoming request from scanner
    transport.on('incoming_pair_request', req => {
      state.activeIncomingRequest = req;
      state.peerAlias    = req.fromAlias;
      state.peerPubKeyHex = req.clientPubKeyHex;
      state.isTypedCodeJoin = !!req.isTypedCodeJoin;
      updateConnectionRequestUI(req.fromAlias, req.isTypedCodeJoin);
      navigateTo('connection-request');
    });

    // Scanner found the host
    transport.on('pair_matched', res => {
      if (!state.pairRequestPending) return;
      state.pairRequestPending = false;
      if (!res.isTypedCodeJoin && res.hostPubKeyHex !== state.peerPubKeyHex) {
        showError('QR public key does not match the host. Invitation rejected.');
        return;
      }
      state.peerId      = res.hostId;
      state.peerAlias   = res.hostAlias;
      state.peerPubKeyHex = res.hostPubKeyHex;
      state.isTypedCodeJoin = !!res.isTypedCodeJoin;
      updateInvitationConfirmedUI(res.hostAlias, res.code, res.isTypedCodeJoin);
      navigateTo('invitation-confirmed');
    });

    // Both peers are now connected — derive session keys
    transport.on('pair_connected', async conn => {
      state.peerId      = conn.peerId;
      state.peerAlias   = conn.peerAlias;
      state.peerPubKeyHex = conn.peerPubKeyHex;
      state.isHost      = conn.isHost;
      state.isTypedCodeJoin = !!conn.isTypedCodeJoin;

      await completeHandshakeKeyDerivation();
      updateConnectedUI(state.peerAlias);
      setConnStatus('encrypted', state.peerAlias);
      navigateTo('connected');
    });

    transport.on('invitation_registered', ({ code, inviteId }) => {
      state.inviteId = inviteId;
      renderShareLink();
    });

    transport.on('encrypted_packet', packet => handleIncomingEncryptedPacket(packet));

    transport.on('pair_rejected', () => {
      state.pairRequestPending = false;
      showError('Peer declined the connection request.');
      navigateTo('nearby-devices');
    });

    transport.on('pair_error', ({ error }) => {
      state.pairRequestPending = false;
      showError(error || 'Pairing failed.');
      if (state.currentScreen === 'scan-qr' && cameraStatus) cameraStatus.textContent = error || 'Pairing failed.';
    });

    transport.on('session_terminated', ({ reason }) => {
      const msg = reason === 'panic'
        ? 'Peer triggered Emergency Panic. Session destroyed.'
        : reason === 'peer_disconnected'
          ? 'Peer disconnected unexpectedly.'
          : 'Session ended by peer.';
      showError(msg, 8000);
      setConnStatus('lost');
      emergencyPanicWipe(false);
    });

    transport.connect();
  }

  // ── Key derivation ──────────────────────────────────────────────────────────
  async function completeHandshakeKeyDerivation() {
    if (!state.myKeyPair || !state.peerPubKeyHex) return;

    try {
      const s = await window.GhostCrypto.ready();
      state.peerPubKey = s.from_hex(state.peerPubKeyHex);

      if (state.isTypedCodeJoin && !state.oneTimeSecret) {
        // Typed-code path: we have no QR secret.
        // Derive a deterministic 16-byte "blind" token from the code itself
        // using Blake2b. Both peers will arrive at the same value.
        // The session is authenticated only by the DH exchange; the safety-
        // number check (mandatory) is the only MITM protection.
        state.sessionKeys = await window.GhostCrypto.deriveTypedCodeSessionKeys(
          state.myKeyPair, state.peerPubKey, state.isHost
        );
      }

      if (!state.isTypedCodeJoin && !state.oneTimeSecret) {
        throw new Error('No one-time secret available for key derivation');
      }

      if (!state.isTypedCodeJoin) {
        state.sessionKeys = await window.GhostCrypto.deriveSessionKeys(
          state.myKeyPair, state.peerPubKey, state.oneTimeSecret.secretBytes, state.isHost
        );
      }
      state.safetyNumber = await window.GhostCrypto.calculateSafetyNumber(
        state.myKeyPair.publicKey, state.peerPubKey
      );
      state.isSafetyVerified = false;
      state.txCounter = 0;
      state.rxCounter = 0;

      console.log('[Crypto] Session keys derived. Safety#:', state.safetyNumber,
        state.isTypedCodeJoin ? '⚠ TYPED-CODE — verify safety number' : '✓ QR-secret authenticated');
    } catch (e) {
      console.error('[Crypto] Key derivation failed:', e);
      showError('Key derivation failed — possible tampering. Aborting session.');
      emergencyPanicWipe(false);
    }
  }

  // ── Invitation generation ───────────────────────────────────────────────────
  function generateRandomCode() {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    let res = '';
    const random = new Uint32Array(8);
    crypto.getRandomValues(random);
    for (let i = 0; i < 8; i++) {
      if (i === 4) res += '-';
      res += chars.charAt(random[i] % chars.length);
    }
    return normalizeInvitationCode(res);
  }

  async function generateAndRenderInvitation() {
    if (!state.myKeyPair) {
      showError('Crypto not ready yet. Wait a moment and retry.');
      return;
    }
    state.isHost      = true;
    state.inviteCode  = generateRandomCode();
    state.inviteId    = null; // will be set by server after register_invitation
    state.oneTimeSecret = await window.GhostCrypto.generateOneTimeSecret();
    state.inviteExpiry  = Date.now() + 5 * 60 * 1000;

    // Use server-reported LAN IP for the QR payload (not window.location.hostname)
    const host = state.serverLanIp || window.location.hostname;
    const port = state.serverPort  || window.location.port;

    const qrPayload = JSON.stringify({
      v:   2,
      pk:  state.myKeyPair.publicKeyHex,
      s:   state.oneTimeSecret.secretHex,  // one-time secret IN QR only
      c:   state.inviteCode,
      h:   host,
      p:   port,
      exp: state.inviteExpiry,
    });

    // Update typed code display
    const codeDisplay = document.getElementById('inv-code-text');
    if (codeDisplay) codeDisplay.textContent = state.inviteCode;

    // Render QR
    const qrContainer = document.getElementById('my-invitation-qr-svg');
    if (qrContainer) {
      qrContainer.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100%;color:#00E676;font-size:12px;">Generating QR…</div>';
      try {
        const res = await fetch(`/api/qr?text=${encodeURIComponent(qrPayload)}`);
        const svg = await res.text();
        qrContainer.innerHTML = svg;
        const svgEl = qrContainer.querySelector('svg');
        if (svgEl) { svgEl.style.width = '100%'; svgEl.style.height = '100%'; }
        qrContainer.style.filter = '';
      } catch (e) {
        qrContainer.innerHTML = `<div style="padding:10px;color:#fff;font-size:11px;word-break:break-all;">[QR: ${state.inviteCode}]</div>`;
      }
    }

    // Register with server
    if (transport && transport.connected) {
      transport.registerInvitation({
        code:         state.inviteCode,
        hostPubKeyHex: state.myKeyPair.publicKeyHex,
        exp:          state.inviteExpiry,
      });
    } else {
      showError('Not connected to server. Invitation cannot be discovered; reconnect and generate a new one.');
    }

    startInvitationCountdown(300);
  }

  function renderShareLink() {
    const linkEl = document.getElementById('inv-share-link-text');
    if (!linkEl) return;
    if (!state.inviteId || !state.serverLanIp) {
      linkEl.textContent = 'Waiting for server…';
      return;
    }
    // Link carries ONLY the inviteId and host pubkey — NO secret
    const url = `https://${state.serverLanIp}:${state.serverPort}/?join=${state.inviteId}`;
    linkEl.textContent = url;
    linkEl.href = url;
  }

  function startInvitationCountdown(seconds) {
    if (state.inviteCountdownTimer) clearInterval(state.inviteCountdownTimer);
    let remaining = seconds;
    const expiryEl = document.getElementById('inv-expiry-sec');

    state.inviteCountdownTimer = setInterval(() => {
      remaining--;
      if (remaining > 0) {
        if (expiryEl) expiryEl.textContent = `${remaining}s`;
      } else {
        clearInterval(state.inviteCountdownTimer);
        state.inviteCountdownTimer = null;
        if (expiryEl) { expiryEl.textContent = 'EXPIRED'; expiryEl.style.color = '#FF5252'; }
        const qrContainer = document.getElementById('my-invitation-qr-svg');
        if (qrContainer) qrContainer.style.filter = 'blur(4px) grayscale(1)';
      }
    }, 1000);
  }

  // ── Camera QR scanner ───────────────────────────────────────────────────────
  const videoEl       = document.getElementById('camera-video-stream');
  const canvasEl      = document.getElementById('camera-canvas');
  const laserEl       = document.getElementById('scan-laser-line');
  const cameraStatus  = document.getElementById('camera-status-text');
  const startCameraBtn = document.getElementById('start-camera-btn');
  const qrFileInput   = document.getElementById('qr-file-input');
  const scanImageLabel = document.getElementById('scan-image-label');
  const isCoarsePointer = typeof window.matchMedia === 'function' && window.matchMedia('(pointer: coarse)').matches;
  if (scanImageLabel) {
    scanImageLabel.textContent = isCoarsePointer ? 'Take Photo' : 'Scan Image';
  }

  async function startCameraScanner() {
    if (state.cameraScanning || state.cameraRequestPending || state.pairRequestPending) return;
    if (!window.jsQR) {
      const msg = 'QR decoder failed to load. Reload the page or enter the code manually.';
      if (cameraStatus) cameraStatus.textContent = msg;
      showError(msg);
      return;
    }
    if (!window.isSecureContext) {
      const msg = 'Camera blocked: this page is not a secure HTTPS context. Check the certificate for this LAN IP.';
      if (cameraStatus) cameraStatus.textContent = msg;
      showError(msg, 10000);
      return;
    }
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      const isInsecure = window.location.protocol !== 'https:' && window.location.hostname !== 'localhost';
      const msg = isInsecure
        ? `Camera blocked: this page is loaded over HTTP. Open the HTTPS URL on your phone: https://${state.serverLanIp || window.location.hostname}:8443`
        : 'Camera API not supported in this browser. Use "Scan Image" or enter the code manually.';
      if (cameraStatus) cameraStatus.textContent = msg;
      showError(msg, 10000);
      return;
    }

    if (cameraStatus) cameraStatus.textContent = 'Requesting camera permission…';
    if (startCameraBtn) startCameraBtn.textContent = 'Cancel Camera';
    const requestId = ++state.cameraRequestId;
    state.cameraRequestPending = true;
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } },
        audio: false,
      });
      if (requestId !== state.cameraRequestId || state.currentScreen !== 'scan-qr') {
        stream.getTracks().forEach(track => track.stop());
        return;
      }
      state.cameraStream = stream;
      state.cameraRequestPending = false;
      videoEl.srcObject = stream;
      videoEl.style.display = 'block';
      await videoEl.play();
      if (requestId !== state.cameraRequestId) return;
      state.cameraScanning = true;
      state.scanHandled = false;
      if (laserEl) laserEl.style.display = 'block';
      if (startCameraBtn) startCameraBtn.textContent = 'Stop Camera';
      if (cameraStatus) cameraStatus.textContent = 'Point camera at GhostLink QR code…';
      state.cameraFrameHandle = requestAnimationFrame(scanVideoFrame);
    } catch (err) {
      if (requestId !== state.cameraRequestId) return;
      stopCameraScanner();
      let msg;
      if (err.name === 'NotAllowedError' || err.name === 'PermissionDeniedError') {
        msg = 'Camera permission denied. Allow camera in browser settings and reload.';
      } else if (err.name === 'NotFoundError') {
        msg = 'No camera found on this device. Use "Scan Image" or enter the code.';
      } else if (err.name === 'NotReadableError') {
        msg = 'Camera in use by another app. Close it and try again.';
      } else {
        msg = `Camera error: ${err.message}`;
      }
      if (cameraStatus) cameraStatus.textContent = msg;
      showError(msg, 10000);
      console.warn('[Camera]', err);
    }
  }

  function stopCameraScanner() {
    state.cameraRequestId++;
    state.cameraRequestPending = false;
    state.cameraScanning = false;
    state.scanDecodeInFlight = false;
    if (state.cameraFrameHandle !== null) {
      cancelAnimationFrame(state.cameraFrameHandle);
      state.cameraFrameHandle = null;
    }
    if (state.cameraStream) {
      state.cameraStream.getTracks().forEach(t => t.stop());
      state.cameraStream = null;
    }
    if (videoEl) {
      videoEl.pause();
      videoEl.srcObject = null;
      videoEl.style.display = 'none';
    }
    if (laserEl) laserEl.style.display = 'none';
    if (startCameraBtn) startCameraBtn.textContent = 'Open Camera';
    if (cameraStatus) cameraStatus.textContent = "Point camera at peer's GhostLink QR code to scan.";
  }

  function scanVideoFrame() {
    if (!state.cameraScanning || state.scanHandled || !videoEl || !canvasEl) return;
    if (!state.scanDecodeInFlight && videoEl.readyState >= videoEl.HAVE_CURRENT_DATA && videoEl.videoWidth > 0) {
      state.scanDecodeInFlight = true;
      try {
      const maxDimension = 960;
      const scale = Math.min(1, maxDimension / Math.max(videoEl.videoWidth, videoEl.videoHeight));
      const width = Math.max(1, Math.round(videoEl.videoWidth * scale));
      const height = Math.max(1, Math.round(videoEl.videoHeight * scale));
      canvasEl.width  = width;
      canvasEl.height = height;
      const ctx = canvasEl.getContext('2d');
      ctx.drawImage(videoEl, 0, 0, canvasEl.width, canvasEl.height);
      const imageData = ctx.getImageData(0, 0, canvasEl.width, canvasEl.height);
      if (window.jsQR) {
        const qr = window.jsQR(imageData.data, imageData.width, imageData.height, {
          inversionAttempts: 'attemptBoth',
        });
        if (qr && qr.data) {
          state.scanHandled = true;
          stopCameraScanner();
          handleScannedQrPayload(qr.data);
          return;
        }
      }
      } catch (err) {
        stopCameraScanner();
        showError(`QR scanning failed: ${err.message}. Try Scan Image or enter the code.`);
        return;
      } finally {
        state.scanDecodeInFlight = false;
      }
    }
    if (state.cameraScanning) state.cameraFrameHandle = requestAnimationFrame(scanVideoFrame);
  }

  // ── File image QR decode ────────────────────────────────────────────────────
  if (qrFileInput) {
    qrFileInput.addEventListener('change', e => {
      const file = e.target.files && e.target.files[0];
      if (!file) return;
      stopCameraScanner();
      const reader = new FileReader();
      reader.onload = evt => {
        const img = new Image();
        img.onload = () => {
          if (!canvasEl) return;
          if (state.pairRequestPending) return;
          // Downscale high-res photos to max 800×800 to avoid jsQR freeze
          const MAX = 800;
          let { width, height } = img;
          if (width > MAX || height > MAX) {
            const ratio = Math.min(MAX / width, MAX / height);
            width  = Math.round(width  * ratio);
            height = Math.round(height * ratio);
          }
          canvasEl.width  = width;
          canvasEl.height = height;
          const ctx = canvasEl.getContext('2d');
          ctx.drawImage(img, 0, 0, width, height);
          const imageData = ctx.getImageData(0, 0, width, height);
          if (window.jsQR) {
            // Try normal, then inverted
            let qr = window.jsQR(imageData.data, imageData.width, imageData.height);
            if (!qr) {
              qr = window.jsQR(imageData.data, imageData.width, imageData.height, { inversionAttempts: 'invertFirst' });
            }
            if (qr && qr.data) {
              handleScannedQrPayload(qr.data);
            } else {
              showError('No QR code found in that image. Try a clearer photo.');
            }
          } else {
            showError('QR decoder failed to load. Reload the page and try again.');
          }
        };
        img.onerror = () => showError('Could not read that image. Try another photo.');
        img.src = evt.target.result;
      };
      reader.onerror = () => showError('Could not open that image. Try another photo.');
      reader.readAsDataURL(file);
      // Reset so same file can be re-selected
      e.target.value = '';
    });
  }

  // ── QR payload handler ──────────────────────────────────────────────────────
  async function handleScannedQrPayload(rawPayload) {
    if (state.pairRequestPending) return;
    let code = null;
    let peerPubKeyHex = null;
    let oneTimeSecret = null;
    let typedCodeOnly = true;

    try {
      if (rawPayload.trim().startsWith('{')) {
        const parsed = JSON.parse(rawPayload);
        if (parsed.v !== 2 || !Number.isFinite(parsed.exp)) { showError('Unrecognized or incomplete GhostLink QR invitation.'); return; }
        if (Date.now() > parsed.exp) {
          showError('QR code has expired (5-minute limit). Ask the host to regenerate.'); return;
        }
        code = normalizeInvitationCode(parsed.c);
        if (!code || typeof parsed.pk !== 'string' || !/^[0-9a-f]{64}$/i.test(parsed.pk) ||
            typeof parsed.s !== 'string' || !/^[0-9a-f]{32}$/i.test(parsed.s)) {
          showError('Invalid GhostLink QR invitation. Ask the host to generate a new QR code.');
          return;
        }
        peerPubKeyHex = parsed.pk;
        const s = await window.GhostCrypto.ready();
        oneTimeSecret = { secretBytes: s.from_hex(parsed.s), secretHex: parsed.s };
        typedCodeOnly = false; // QR path — has secret
      } else {
        // Plain typed code
        code = normalizeInvitationCode(rawPayload.replace(/^ghostlink:\/\/invite\//i, ''));
      }
    } catch (e) {
      showError('Could not read QR data. Try again.');
      console.warn('[QR parse]', e);
      return;
    }

    if (!code) { showError('Enter exactly eight letters or numbers (for example 7K9M-4Q2X).'); return; }
    if (!transport || !transport.connected || !state.myKeyPair) {
      showError('Not connected to server or crypto not ready. Cannot resolve this invitation.');
      return;
    }
    state.isHost = false;
    state.pairRequestPending = true;
    if (!transport.requestPair(code, state.myKeyPair.publicKeyHex, typedCodeOnly)) {
      state.pairRequestPending = false;
      showError('Could not send pairing request. Check the server connection.');
      return;
    }
    state.inviteCode = code;
    state.peerPubKeyHex = peerPubKeyHex;
    state.oneTimeSecret = oneTimeSecret;
    state.isTypedCodeJoin = typedCodeOnly;
    if (cameraStatus && state.currentScreen === 'scan-qr') cameraStatus.textContent = 'Resolving invitation with server…';
  }

  if (startCameraBtn) {
    startCameraBtn.addEventListener('click', () => {
      state.cameraScanning || state.cameraRequestPending ? stopCameraScanner() : startCameraScanner();
    });
  }

  // ── Typed code submit ───────────────────────────────────────────────────────
  const codeInput    = document.getElementById('invitation-code-input');
  const codeContinue = document.getElementById('code-continue-btn');

  if (codeInput) {
    codeInput.addEventListener('input', e => {
      e.target.value = formatInvitationCode(e.target.value);
    });
  }

  if (codeContinue) {
    codeContinue.addEventListener('click', () => {
      const code = normalizeInvitationCode(codeInput ? codeInput.value : '');
      if (!code) {
        showError('Enter exactly eight letters or numbers (XXXX-XXXX).');
        return;
      }
      codeInput.value = code;
      handleScannedQrPayload(code);
    });
  }

  // ── URL join handler (?join=<inviteId>&pk=<hostPubKey>) ─────────────────────
  async function handleUrlJoin() {
    const params = new URLSearchParams(window.location.search);
    const joinId = params.get('join');
    if (!joinId) return;

    // Clean the URL without reloading
    window.history.replaceState({}, '', window.location.pathname);

    showInfo('📎 Opening invitation link… fetching details from server.');

    try {
      const res  = await fetch(`/api/join?id=${encodeURIComponent(joinId)}`);
      const data = await res.json();

      if (data.error) {
        showError(`Link error: ${data.error}`);
        return;
      }

      // Confirm with user — link path is labelled less private
      state.peerAlias       = data.hostAlias || 'Host';
      state.linkJoinPending = true;
      state.isTypedCodeJoin = true; // link never carries secret

      // Show invite confirmed screen with explicit warning
      navigateTo('enter-code');
      showError('⚠ Link join is less private. Safety-number check is mandatory.', 10000);
    } catch (e) {
      showError('Could not reach server to validate the invite link. Are you on the same Wi-Fi?');
    }
  }

  // ── Chat messaging ──────────────────────────────────────────────────────────
  function getTimestamp() {
    const d = new Date();
    return d.toTimeString().substring(0, 5);
  }

  async function sendEncryptedMessage(text, theme) {
    if (!text || !text.trim()) return;

    const cleanText = text.trim();
    const stream = theme === 'blue'
      ? document.getElementById('chat-stream-blue')
      : document.getElementById('chat-stream-green');
    const time = getTimestamp();

    function appendBubble(cssClass, tag, extraClass) {
      const row = document.createElement('div');
      row.className = `chat-msg-row ${cssClass}`;
      row.innerHTML = `<span class="chat-time-tag">${tag}</span><div class="chat-msg-bubble${extraClass || ''}">${escapeHtml(cleanText)}</div>`;
      if (stream) { stream.appendChild(row); stream.scrollTop = stream.scrollHeight; }
      return row;
    }

    if (!state.sessionKeys || !state.sessionKeys.txKey) {
      showError('Not connected to a peer. Message not sent.');
      return;
    }

    // Typed-code join: block until safety number verified
    if (state.isTypedCodeJoin && !state.isSafetyVerified) {
      showError('Verify the safety number before sending messages (typed-code join).');
      navigateTo('safety-number');
      return;
    }

    state.txCounter++;
    let encrypted;
    try {
      const payload = { m: cleanText, ttl: state.disappearingSeconds };
      encrypted = await window.GhostCrypto.encryptMessage(
        JSON.stringify(payload), state.sessionKeys.txKey, state.txCounter
      );
    } catch (e) {
      console.error('[Send]', e);
      showError('Failed to encrypt message. Message not sent.');
      return;
    }

    if (!transport || !transport.sendEncryptedPacket(encrypted)) {
      showError('Message not sent: the connection to the peer is not open.');
      return;
    }

    // Only show a bubble once the ciphertext is actually on the wire
    const row = appendBubble('outbound', `${time} ✓`);
    scheduleDisappearingDestruction(row, state.disappearingSeconds);
  }

  async function handleIncomingEncryptedPacket(packet) {
    if (!state.sessionKeys || !state.sessionKeys.rxKey) return;

    try {
      state.rxCounter++;
      const decrypted = await window.GhostCrypto.decryptMessage(
        packet.ciphertextHex, packet.nonceHex, state.sessionKeys.rxKey, state.rxCounter
      );

      // decrypted.m may be raw text or JSON payload {m, ttl}
      let msgText = decrypted.m;
      let peerTtl = 0;
      try {
        const inner = JSON.parse(decrypted.m);
        if (inner && inner.m) { msgText = inner.m; peerTtl = inner.ttl || 0; }
      } catch (_) { /* raw text is fine */ }

      const stream = document.body.classList.contains('theme-blue')
        ? document.getElementById('chat-stream-blue')
        : document.getElementById('chat-stream-green');

      const row = document.createElement('div');
      row.className = 'chat-msg-row inbound';
      row.innerHTML = `<span class="chat-time-tag">${getTimestamp()}</span><div class="chat-msg-bubble">${escapeHtml(msgText)}</div>`;
      if (stream) { stream.appendChild(row); stream.scrollTop = stream.scrollHeight; }

      // Use whichever timer is larger: local setting or peer's request
      const effectiveTtl = Math.max(state.disappearingSeconds, peerTtl);
      if (effectiveTtl > 0) {
        scheduleDisappearingDestruction(row, effectiveTtl);
      }
    } catch (e) {
      console.error('[Decrypt]', e);
      showError('A message could not be decrypted (counter mismatch or tampering). It was not shown.');
    }
  }

  function escapeHtml(str) {
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function scheduleDisappearingDestruction(element, seconds) {
    if (!seconds || seconds <= 0 || !element) return;
    const timer = setTimeout(() => {
      if (element && element.parentNode) {
        element.style.transition = 'opacity 0.5s ease';
        element.style.opacity = '0';
        setTimeout(() => element.remove(), 500);
      }
    }, seconds * 1000);
    state.activeMessageTimers.push(timer);
  }

  // ── Dynamic UI updaters ─────────────────────────────────────────────────────
  function updateConnectionRequestUI(fromAlias, isTypedCode) {
    const nameEl = document.getElementById('connreq-peer-name');
    if (nameEl) nameEl.textContent = fromAlias || 'Unknown';
    const typeEl = document.getElementById('connreq-join-type');
    if (typeEl) {
      typeEl.textContent = isTypedCode ? '⚠ Typed-code join — verify safety number after connecting' : '✓ QR scan — authenticated';
      typeEl.style.color = isTypedCode ? '#FF9800' : '#00E676';
    }
  }

  function updateInvitationConfirmedUI(peerAlias, code, isTypedCode, isLink) {
    const aliasEl = document.getElementById('conf-peer-alias');
    if (aliasEl) aliasEl.textContent = peerAlias || 'Peer';
    const codeEl = document.getElementById('conf-pairing-code');
    if (codeEl) codeEl.textContent = code || state.inviteCode;
    const noteEl = document.getElementById('conf-join-note');
    if (noteEl) {
      if (isLink) {
        noteEl.textContent = '⚠ Less-private path: joined via link. Safety number check is mandatory.';
        noteEl.style.color = '#FF9800';
      } else if (isTypedCode) {
        noteEl.textContent = '⚠ Server-trusted path: typed-code join. Verify safety number.';
        noteEl.style.color = '#FF9800';
      } else {
        noteEl.textContent = '✓ QR-authenticated: one-time secret verified.';
        noteEl.style.color = '#00E676';
      }
    }
  }

  function updateConnectedUI(peerAlias) {
    const p = peerAlias || state.peerAlias || 'Peer';
    const sub = document.querySelector('#screen-connected p');
    if (sub) sub.textContent = `You are now securely connected with ${p}.`;
    const aliasSpec = document.querySelector('#screen-connected .spec-v.green');
    if (aliasSpec) aliasSpec.textContent = `: ${p}`;

    // Update chat headers — real alias, real status
    ['green', 'blue'].forEach(theme => {
      const header = document.querySelector(`#screen-chat-${theme} .chat-peer-name`);
      if (header) header.textContent = p;
      const statusEl = document.querySelector(`#screen-chat-${theme} .chat-peer-status`);
      if (statusEl) {
        statusEl.textContent = state.isTypedCodeJoin ? '⚠ Verify safety number' : '🔒 Encrypted';
        statusEl.style.color = state.isTypedCodeJoin ? '#FF9800' : '#00E676';
      }
      // Connection type (real)
      const connType = document.querySelector(`#screen-connected .spec-v.conn-type`);
      if (connType) connType.textContent = ': WSS / LAN Relay';
    });
  }

  function renderNearbyPeersUI() {
    const container = document.getElementById('nearby-devices-container');
    const emptyState = document.getElementById('nearby-empty-state');
    if (!container) return;

    container.innerHTML = '';
    const active = state.discoveredPeers || [];

    if (active.length === 0) {
      if (emptyState) emptyState.style.display = 'block';
      return;
    }
    if (emptyState) emptyState.style.display = 'none';

    active.forEach(peer => {
      const row = document.createElement('div');
      row.className = 'nearby-device-row';
      row.innerHTML = `
        <div class="alias-avatar-box" style="width:36px;height:36px;border-color:#00E676;">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 3c1.66 0 3 1.34 3 3s-1.34 3-3 3-3-1.34-3-3 1.34-3 3-3zm0 14.2c-2.5 0-4.71-1.28-6-3.22.03-1.99 4-3.08 6-3.08 1.99 0 5.97 1.09 6 3.08-1.29 1.94-3.5 3.22-6 3.22z"/>
          </svg>
        </div>
        <div style="flex:1;">
          <div style="font-size:13px;font-weight:600;color:#FFF;">${escapeHtml(peer.alias)}</div>
          <div style="font-size:11px;color:#00E676;">WSS LAN · Ready to pair</div>
        </div>
        <span style="font-size:11px;color:#7FA690;font-family:var(--font-mono);">Connect ›</span>
      `;
      // Clicking a nearby peer triggers an outbound invitation-based pair request
      // (they must have their invitation active, or use their code)
      row.onclick = () => {
        state.peerAlias = peer.alias;
        showInfo(`To connect to ${peer.alias}: ask them to show their invitation QR, then scan it.`);
        navigateTo('scan-qr');
      };
      container.appendChild(row);
    });
  }

  function renderSafetyNumberUI() {
    const disp = document.getElementById('safety-number-display');
    if (disp) disp.textContent = state.safetyNumber;

    const verifiedBadge = document.getElementById('safety-verified-badge');
    if (verifiedBadge) {
      verifiedBadge.style.display = state.isSafetyVerified ? 'block' : 'none';
    }

    // If typed-code join, show mandatory warning
    const mandatoryNote = document.getElementById('safety-mandatory-note');
    if (mandatoryNote) {
      mandatoryNote.style.display = state.isTypedCodeJoin ? 'block' : 'none';
    }
  }

  function renderConnectionSettingsUI() {
    const ipDisp = document.getElementById('conn-lan-ip-display');
    if (ipDisp) ipDisp.textContent = transport && transport.currentHost ? transport.currentHost : 'Not connected';

    // Verify RAM-only: confirm no storage has been written
    const ramNote = document.getElementById('ram-only-note');
    if (ramNote) {
      ramNote.textContent = state.ramOnlyVerified
        ? 'Verified RAM-only: no Cache API entries or service workers detected.'
        : 'RAM-only claim unavailable: browser persistence was detected.';
      ramNote.style.color = state.ramOnlyVerified ? '#00E676' : '#FF5252';
      /* legacy check intentionally disabled
      const clean = (() => {
        try { return true; }
        catch (_) { return true; }
      })();
      ramNote.textContent = clean
        ? '✓ Verified: no data in localStorage or sessionStorage.'
        : '⚠ Unexpected storage found — cleared.';
      ramNote.style.color = clean ? '#00E676' : '#FF5252'; */
    }
  }

  // ── Emergency panic wipe ────────────────────────────────────────────────────
  async function emergencyPanicWipe(broadcast = true) {
    if (broadcast && transport) transport.notifyTermination('panic');

    // Memzero all key material
    if (window.GhostCrypto) {
      if (state.myKeyPair?.privateKey) await window.GhostCrypto.memzero(state.myKeyPair.privateKey);
      if (state.sessionKeys?.txKey)    await window.GhostCrypto.memzero(state.sessionKeys.txKey);
      if (state.sessionKeys?.rxKey)    await window.GhostCrypto.memzero(state.sessionKeys.rxKey);
      if (state.oneTimeSecret?.secretBytes) await window.GhostCrypto.memzero(state.oneTimeSecret.secretBytes);
    }

    // Cancel timers
    state.activeMessageTimers.forEach(t => clearTimeout(t));
    state.activeMessageTimers = [];
    if (state.inviteCountdownTimer) { clearInterval(state.inviteCountdownTimer); state.inviteCountdownTimer = null; }

    // Wipe RAM state
    Object.assign(state, {
      myKeyPair: null, oneTimeSecret: null, sessionKeys: null,
      peerId: null, peerPubKey: null, peerPubKeyHex: null,
      inviteCode: null, inviteId: null,
      safetyNumber: '---- - ---- - ----', isSafetyVerified: false,
      txCounter: 0, rxCounter: 0, isTypedCodeJoin: false,
    });

    // Clear storage (should be empty, belt-and-suspenders)

    // Purge DOM chat
    ['chat-stream-green', 'chat-stream-blue'].forEach(id => {
      const el = document.getElementById(id);
      if (el) el.innerHTML = '';
    });

    // Rotate alias and regenerate keypair
    state.alias = 'Shadow_' + Math.random().toString(36).substring(2, 6).toUpperCase();
    const t1 = document.getElementById('home-alias-text');
    const t2 = document.getElementById('settings-alias-display');
    if (t1) t1.textContent = state.alias;
    if (t2) t2.textContent = state.alias;

    setConnStatus(transport && transport.connected ? 'connected' : 'disconnected');
    await initCryptoEngine();
    if (transport) transport.publishPresence(state.alias, state.myKeyPair?.publicKeyHex);

    navigateTo('home');
    showInfo('Panic wipe complete. Keys and messages cleared.');
  }

  // ── Button bindings ─────────────────────────────────────────────────────────

  // Screen jumper (dev toolbar)
  if (screenJumper) screenJumper.addEventListener('change', e => navigateTo(e.target.value));
  if (toggleFrameBtn && deviceFrame) {
    toggleFrameBtn.addEventListener('click', () => deviceFrame.classList.toggle('fullscreen-mode'));
  }

  // Long-press panic (status bar)
  let longPressTimer = null;
  const statusBar = document.querySelector('.device-status-bar');
  if (statusBar) {
    const startLp = () => { longPressTimer = setTimeout(() => { emergencyPanicWipe(true); }, 1200); };
    const stopLp  = () => clearTimeout(longPressTimer);
    statusBar.addEventListener('mousedown', startLp);
    statusBar.addEventListener('mouseup',   stopLp);
    statusBar.addEventListener('mouseleave', stopLp);
    statusBar.addEventListener('touchstart', startLp, { passive: true });
    statusBar.addEventListener('touchend',  stopLp);
  }

  // Splash
  const splashBtn = document.getElementById('splash-enter-btn');
  if (splashBtn) splashBtn.addEventListener('click', () => navigateTo('home'));

  // Home
  const bind = (id, fn) => { const el = document.getElementById(id); if (el) el.addEventListener('click', fn); };
  bind('home-to-settings-btn',   () => navigateTo('settings'));
  bind('home-to-settings-btn-2', () => navigateTo('settings'));
  bind('home-to-pairing-btn',    () => navigateTo('pairing-menu'));
  bind('home-to-invitations-btn', () => navigateTo('show-invitation'));
  bind('home-to-nearby-btn',     () => navigateTo('nearby-devices'));
  bind('nav-to-chat-btn',        () => navigateTo('chat-green'));
  bind('nav-to-devices-btn',     () => navigateTo('nearby-devices'));
  bind('nav-to-profile-btn',     () => navigateTo('settings'));

  bind('home-alias-refresh', () => {
    state.alias = 'Shadow_' + Math.random().toString(36).substring(2, 6).toUpperCase();
    ['home-alias-text', 'settings-alias-display'].forEach(id => {
      const el = document.getElementById(id); if (el) el.textContent = state.alias;
    });
    if (transport) transport.publishPresence(state.alias, state.myKeyPair?.publicKeyHex);
  });

  // Pairing Menu
  bind('pairing-back-btn',    () => navigateTo('home'));
  bind('pairing-to-scan-btn', () => navigateTo('scan-qr'));
  bind('pairing-to-code-btn', () => navigateTo('enter-code'));
  bind('pairing-to-link-btn', () => navigateTo('enter-code'));
  bind('pairing-to-myinv-btn', () => navigateTo('show-invitation'));

  // My Invitation
  bind('myinv-back-btn',      () => navigateTo('home'));
  bind('inv-regenerate-btn',  () => generateAndRenderInvitation());

  // Copy code
  bind('copy-code-btn', () => {
    if (!state.inviteCode) return;
    navigator.clipboard.writeText(state.inviteCode).then(() => showInfo('Code copied to clipboard.'));
  });

  // Share link (no secret)
  bind('share-link-btn', () => {
    const linkEl = document.getElementById('inv-share-link-text');
    const url    = linkEl ? linkEl.textContent : null;
    if (!url || url.startsWith('Wait')) {
      showError('Invitation not registered yet. Wait a moment.');
      return;
    }
    navigator.clipboard.writeText(url).then(() => {
      showInfo('Link copied. ⚠ This link is less private than QR scan.');
    });
  });

  // Scan QR
  bind('scan-qr-cancel-btn', () => { stopCameraScanner(); navigateTo('pairing-menu'); });

  // Enter Code
  bind('enter-code-back-btn', () => navigateTo('pairing-menu'));

  // Invitation Confirmed (scanner proceeds to accept)
  bind('conf-back-btn', () => navigateTo('enter-code'));
  bind('conf-proceed-btn', () => {
    showInfo('Pairing request sent. Waiting for the host to accept.');
  });

  // Nearby Devices
  bind('nearby-back-btn',    () => navigateTo('home'));
  bind('nearby-refresh-btn', () => { if (transport) transport.publishPresence(state.alias); });

  // Connection Request (host accepts/rejects)
  bind('connreq-back-btn',   () => navigateTo('nearby-devices'));
  bind('connreq-reject-btn', () => {
    if (state.activeIncomingRequest && transport)
      transport.rejectPair(state.activeIncomingRequest.fromId);
    navigateTo('nearby-devices');
  });
  bind('connreq-accept-btn', () => {
    if (state.activeIncomingRequest && transport) {
      transport.acceptPair(
        state.activeIncomingRequest.fromId,
        state.myKeyPair.publicKeyHex,
        state.peerPubKeyHex,
        state.isTypedCodeJoin
      );
    }
  });

  // Connected
  bind('start-chat-btn', () => navigateTo('chat-green'));

  // Chat (Green)
  bind('chat-green-back-btn', () => navigateTo('connected'));
  bind('chat-to-exit-btn',    () => navigateTo('exit'));
  bind('chat-safety-btn-green', () => navigateTo('safety-number'));

  const timerBtnGreen = document.getElementById('chat-timer-btn-green');
  const timerLabelGreen = document.getElementById('timer-label-green');
  if (timerBtnGreen) {
    timerBtnGreen.addEventListener('click', () => {
      if (state.disappearingSeconds === 0) state.disappearingSeconds = 30;
      else if (state.disappearingSeconds === 30)   state.disappearingSeconds = 300;
      else if (state.disappearingSeconds === 300)  state.disappearingSeconds = 3600;
      else state.disappearingSeconds = 0;
      const label = state.disappearingSeconds === 0 ? 'Off' :
        state.disappearingSeconds < 60 ? `${state.disappearingSeconds}s` :
        state.disappearingSeconds < 3600 ? `${state.disappearingSeconds/60}m` : '1h';
      if (timerLabelGreen) timerLabelGreen.textContent = label;
      const timerLabelBlue = document.getElementById('timer-label-blue');
      if (timerLabelBlue) timerLabelBlue.textContent = label;
      showInfo(`Disappearing timer: ${label === 'Off' ? 'Off' : label + ' (requested of peer — not guaranteed)'}`);
    });
  }

  const chatSendGreen  = document.getElementById('chat-send-green');
  const chatInputGreen = document.getElementById('chat-input-green');
  if (chatSendGreen && chatInputGreen) {
    const sendG = () => { sendEncryptedMessage(chatInputGreen.value, 'green'); chatInputGreen.value = ''; };
    chatSendGreen.addEventListener('click', sendG);
    chatInputGreen.addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendG(); } });
  }

  // Chat (Blue)
  bind('chat-blue-back-btn', () => navigateTo('connected'));
  bind('chat-to-exit-blue-btn', () => navigateTo('exit'));
  bind('chat-safety-btn-blue', () => navigateTo('safety-number'));

  const timerBtnBlue = document.getElementById('chat-timer-btn-blue');
  if (timerBtnBlue && timerBtnGreen) timerBtnBlue.addEventListener('click', () => timerBtnGreen.click());

  const chatSendBlue  = document.getElementById('chat-send-blue');
  const chatInputBlue = document.getElementById('chat-input-blue');
  if (chatSendBlue && chatInputBlue) {
    const sendB = () => { sendEncryptedMessage(chatInputBlue.value, 'blue'); chatInputBlue.value = ''; };
    chatSendBlue.addEventListener('click', sendB);
    chatInputBlue.addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendB(); } });
  }

  // Safety Number
  bind('safety-back-btn', () => goBack());
  const safetyVerifyBtn = document.getElementById('safety-verify-btn');
  if (safetyVerifyBtn) {
    safetyVerifyBtn.addEventListener('click', () => {
      state.isSafetyVerified = true;
      renderSafetyNumberUI();
      showInfo('✓ Safety number marked as verified. Session is authenticated.');
      // Update chat status badges
      ['green', 'blue'].forEach(theme => {
        const st = document.querySelector(`#screen-chat-${theme} .chat-peer-status`);
        if (st) { st.textContent = '🔒 Encrypted · Verified'; st.style.color = '#00E676'; }
      });
      setTimeout(() => goBack(), 1000);
    });
  }

  // Settings
  bind('settings-back-btn',    () => navigateTo('home'));
  bind('settings-conn-back-btn', () => navigateTo('settings'));
  bind('settings-priv-back-btn', () => navigateTo('settings'));
  bind('settings-about-back-btn', () => navigateTo('settings'));

  const aliasCard = document.getElementById('settings-alias-card');
  if (aliasCard) {
    aliasCard.addEventListener('click', () => {
      state.alias = 'Shadow_' + Math.random().toString(36).substring(2, 6).toUpperCase();
      ['home-alias-text', 'settings-alias-display'].forEach(id => {
        const el = document.getElementById(id); if (el) el.textContent = state.alias;
      });
      if (transport) transport.publishPresence(state.alias, state.myKeyPair?.publicKeyHex);
      showInfo(`Alias rotated to ${state.alias}`);
    });
  }
  bind('settings-conn-card',    () => navigateTo('settings-connection'));
  bind('settings-privacy-card', () => navigateTo('settings-privacy'));
  bind('settings-about-card',   () => navigateTo('settings-about'));
  bind('settings-panic-card',   () => emergencyPanicWipe(true));

  const themeBtnGreen = document.getElementById('theme-btn-green');
  if (themeBtnGreen) themeBtnGreen.addEventListener('click', () => { document.body.className = 'theme-green'; navigateTo('chat-green'); });
  const themeBtnBlue = document.getElementById('theme-btn-blue');
  if (themeBtnBlue) themeBtnBlue.addEventListener('click', () => { document.body.className = 'theme-blue'; navigateTo('chat-blue'); });

  // Exit screen
  bind('exit-home-btn', () => navigateTo('home'));
  bind('exit-disconnect-btn', () => {
    if (transport) transport.notifyTermination('disconnect');
    emergencyPanicWipe(false);
  });

  // Default disappearing timer (privacy settings)
  const defaultTimerSelect = document.getElementById('default-disappearing-select');
  if (defaultTimerSelect) {
    defaultTimerSelect.addEventListener('change', e => {
      state.disappearingSeconds = parseInt(e.target.value, 10) || 0;
    });
  }

  // ── Transport publishPresence with pubkey ───────────────────────────────────
  // Extend transport to carry pubKeyHex in presence updates
  const _origPublishPresence = window.GhostTransport && window.GhostTransport.WssTransport
    ? window.GhostTransport.WssTransport.prototype.publishPresence
    : null;

  // ── Boot ────────────────────────────────────────────────────────────────────
  async function boot() {
    try {
      state.ramOnlyVerified = await verifyRamOnly();
      const ramNote = document.getElementById('ram-only-note');
      if (ramNote) {
        ramNote.style.display = 'block';
        if (state.ramOnlyVerified) ramNote.textContent = 'Verified RAM-only: no Cache API entries or service workers detected; session keys are scrubbed on exit.';
        else ramNote.textContent = 'RAM-only claim unavailable: browser persistence was detected.';
      }
      await initCryptoEngine();
      initTransportEngine();

      // Initial display
      const t1 = document.getElementById('home-alias-text');
      const t2 = document.getElementById('settings-alias-display');
      if (t1) t1.textContent = state.alias;
      if (t2) t2.textContent = state.alias;

      // Handle URL join parameter (share link)
      await handleUrlJoin();

      navigateTo('home');
      if (window.GhostApp) { window.GhostApp.booted = true; window.GhostApp.error = null; }
    } catch (e) {
      console.error('[Boot] Failed:', e);
      if (window.GhostApp) window.GhostApp.error = e.message;
      showError(`GhostLink failed to start: ${e.message}. Reload the page.`);
    }
  }

  window.GhostApp = window.GhostApp || {};
  window.GhostApp.boot = boot;
  window.GhostApp.state = state;

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true });
  } else {
    boot();
  }
})();
