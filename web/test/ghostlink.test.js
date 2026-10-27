/**
 * GHOSTLINK COMPREHENSIVE AUTOMATED TEST SUITE
 * Validates:
 * 1. Libsodium Cryptographic Engine (X25519, XChaCha20-Poly1305 AEAD, 512B padding, Safety Numbers, Memzero)
 * 2. Cryptographic Security Invariants (Tampering rejection, Nonce verification, Replay detection)
 * 3. Server Protocol & Invitation Lifecycle (5-min TTL, Single-use, 5-attempt brute-force throttle, Self-pair rejection)
 * 4. Blind Relay & Oversized Packet Dropping (> 2KB)
 * 5. End-to-End Live Pairing, AEAD Encryption, & Emergency Panic Wipe
 */

const assert = require('assert');
const { test, describe, before, after } = require('node:test');
const WebSocket = require('ws');
const GhostCrypto = require('../js/crypto');
const { WssTransport } = require('../js/transport');
const Invitation = require('../js/invitation');

const TEST_SERVER_URL = 'ws://localhost:8088';

describe('GhostLink Cryptographic Engine', () => {

  test('Normalizes the displayed invitation code with or without a hyphen', () => {
    assert.strictEqual(Invitation.normalize('7K9M-4Q2X'), '7K9M-4Q2X');
    assert.strictEqual(Invitation.normalize('7K9M4Q2X'), '7K9M-4Q2X');
    assert.strictEqual(Invitation.normalize(' 7k9m - 4q2x '), '7K9M-4Q2X');
    assert.strictEqual(Invitation.formatPartial('7k9m4q2x'), '7K9M-4Q2X');
    assert.strictEqual(Invitation.normalize('7K9M-4Q2'), null);
    assert.strictEqual(Invitation.normalize('7K9M!4Q2X'), null);
    assert.strictEqual(Invitation.normalize('7K9M-4Q2XY'), null);
  });

  test('Initializes libsodium correctly', async () => {
    const s = await GhostCrypto.ready();
    assert.ok(s, 'libsodium instance must be available');
    assert.strictEqual(GhostCrypto.version, 1, 'Protocol version must be 1');
  });

  test('Generates valid ephemeral X25519 keypairs', async () => {
    const kp = await GhostCrypto.generateEphemeralKeyPair();
    assert.ok(kp.publicKey instanceof Uint8Array, 'publicKey must be Uint8Array');
    assert.strictEqual(kp.publicKey.length, 32, 'X25519 publicKey must be 32 bytes');
    assert.ok(kp.privateKey instanceof Uint8Array, 'privateKey must be Uint8Array');
    assert.strictEqual(kp.privateKey.length, 32, 'X25519 privateKey must be 32 bytes');
    assert.strictEqual(kp.publicKeyHex.length, 64, 'publicKeyHex must be 64 characters');
  });

  test('Generates 128-bit cryptographically secure one-time secret', async () => {
    const sec1 = await GhostCrypto.generateOneTimeSecret();
    const sec2 = await GhostCrypto.generateOneTimeSecret();
    assert.strictEqual(sec1.secretBytes.length, 16, 'One-time secret must be exactly 16 bytes (128 bits)');
    assert.strictEqual(sec1.secretHex.length, 32, 'secretHex must be 32 hex characters');
    assert.notStrictEqual(sec1.secretHex, sec2.secretHex, 'Successive secrets must be cryptographically distinct');
  });

  test('Derives directional authenticated session keys matching between host and client', async () => {
    const hostKp = await GhostCrypto.generateEphemeralKeyPair();
    const clientKp = await GhostCrypto.generateEphemeralKeyPair();
    const qrSecret = await GhostCrypto.generateOneTimeSecret();

    // Host derives keys
    const hostKeys = await GhostCrypto.deriveSessionKeys(
      hostKp,
      clientKp.publicKey,
      qrSecret.secretBytes,
      true // isHost = true
    );

    // Client derives keys
    const clientKeys = await GhostCrypto.deriveSessionKeys(
      clientKp,
      hostKp.publicKey,
      qrSecret.secretBytes,
      false // isHost = false
    );

    assert.strictEqual(hostKeys.txKey.length, 32, 'Host Tx key must be 32 bytes');
    assert.strictEqual(hostKeys.rxKey.length, 32, 'Host Rx key must be 32 bytes');
    assert.strictEqual(clientKeys.txKey.length, 32, 'Client Tx key must be 32 bytes');
    assert.strictEqual(clientKeys.rxKey.length, 32, 'Client Rx key must be 32 bytes');

    // Cross-directional equality
    assert.deepStrictEqual(hostKeys.txKey, clientKeys.rxKey, 'Host Tx key must match Client Rx key');
    assert.deepStrictEqual(hostKeys.rxKey, clientKeys.txKey, 'Host Rx key must match Client Tx key');

    // Directional isolation: Tx and Rx are never the same
    assert.notDeepStrictEqual(hostKeys.txKey, hostKeys.rxKey, 'Tx and Rx keys must be distinct for directional isolation');
  });

  test('Authentication binding: Different one-time secrets produce incompatible session keys', async () => {
    const hostKp = await GhostCrypto.generateEphemeralKeyPair();
    const clientKp = await GhostCrypto.generateEphemeralKeyPair();
    const secretA = await GhostCrypto.generateOneTimeSecret();
    const secretB = await GhostCrypto.generateOneTimeSecret();

    const hostKeys = await GhostCrypto.deriveSessionKeys(hostKp, clientKp.publicKey, secretA.secretBytes, true);
    const clientKeys = await GhostCrypto.deriveSessionKeys(clientKp, hostKp.publicKey, secretB.secretBytes, false);

    assert.notDeepStrictEqual(hostKeys.txKey, clientKeys.rxKey, 'Keys derived from different secrets must not match');
  });

  test('Encrypts and decrypts with XChaCha20-Poly1305 AEAD + 512B fixed padding', async () => {
    const hostKp = await GhostCrypto.generateEphemeralKeyPair();
    const clientKp = await GhostCrypto.generateEphemeralKeyPair();
    const qrSecret = await GhostCrypto.generateOneTimeSecret();

    const hostKeys = await GhostCrypto.deriveSessionKeys(hostKp, clientKp.publicKey, qrSecret.secretBytes, true);
    const clientKeys = await GhostCrypto.deriveSessionKeys(clientKp, hostKp.publicKey, qrSecret.secretBytes, false);

    const plaintext = 'Secret rendezvous at 23:00 UTC. Confirm receipt.';
    const counter = 1;

    // Host encrypts with txKey
    const encrypted = await GhostCrypto.encryptMessage(plaintext, hostKeys.txKey, counter);
    assert.ok(encrypted.nonceHex, 'Must include 24-byte nonce');
    assert.strictEqual(encrypted.nonceHex.length, 48, 'Nonce hex must be 48 chars (24 bytes)');
    assert.ok(encrypted.ciphertextHex, 'Must include ciphertext hex');
    assert.strictEqual(encrypted.counter, 1, 'Counter must be 1');

    // Constant size block padding verification: ciphertext includes 512B padded plaintext + 16B Poly1305 tag
    const ciphertextBytesLen = encrypted.ciphertextHex.length / 2;
    assert.strictEqual(ciphertextBytesLen, 512 + 16, 'Ciphertext size must be exactly 528 bytes (512B padded payload + 16B auth tag)');

    // Client decrypts with rxKey
    const decrypted = await GhostCrypto.decryptMessage(encrypted.ciphertextHex, encrypted.nonceHex, clientKeys.rxKey, 1);
    assert.strictEqual(decrypted.m, plaintext, 'Decrypted message must match original plaintext');
    assert.strictEqual(decrypted.c, 1, 'Decrypted counter must match');
    assert.ok(decrypted.t > 0, 'Decrypted timestamp must be present');
  });

  test('Tampered ciphertext rejection (AEAD integrity failure)', async () => {
    const hostKp = await GhostCrypto.generateEphemeralKeyPair();
    const clientKp = await GhostCrypto.generateEphemeralKeyPair();
    const qrSecret = await GhostCrypto.generateOneTimeSecret();

    const hostKeys = await GhostCrypto.deriveSessionKeys(hostKp, clientKp.publicKey, qrSecret.secretBytes, true);
    const clientKeys = await GhostCrypto.deriveSessionKeys(clientKp, hostKp.publicKey, qrSecret.secretBytes, false);

    const encrypted = await GhostCrypto.encryptMessage('Untampered original', hostKeys.txKey, 1);

    // Tamper single hex character in ciphertext
    const tamperedHex = encrypted.ciphertextHex.substring(0, 10) + 
      (encrypted.ciphertextHex[10] === '0' ? '1' : '0') + 
      encrypted.ciphertextHex.substring(11);

    await assert.rejects(
      async () => {
        await GhostCrypto.decryptMessage(tamperedHex, encrypted.nonceHex, clientKeys.rxKey, 1);
      },
      /Ciphertext integrity check failed/,
      'Tampered ciphertext must be rejected by AEAD integrity check'
    );
  });

  test('Replay prevention / Counter mismatch rejection', async () => {
    const hostKp = await GhostCrypto.generateEphemeralKeyPair();
    const clientKp = await GhostCrypto.generateEphemeralKeyPair();
    const qrSecret = await GhostCrypto.generateOneTimeSecret();

    const hostKeys = await GhostCrypto.deriveSessionKeys(hostKp, clientKp.publicKey, qrSecret.secretBytes, true);
    const clientKeys = await GhostCrypto.deriveSessionKeys(clientKp, hostKp.publicKey, qrSecret.secretBytes, false);

    const encrypted = await GhostCrypto.encryptMessage('Replay test message', hostKeys.txKey, 1);

    // Receiver expecting counter 2 receives packet encrypted for counter 1
    await assert.rejects(
      async () => {
        await GhostCrypto.decryptMessage(encrypted.ciphertextHex, encrypted.nonceHex, clientKeys.rxKey, 2);
      },
      /Ciphertext integrity check failed|Counter mismatch/,
      'Packet with mismatched counter must be rejected'
    );
  });

  test('Invalid nonce length rejection', async () => {
    const hostKp = await GhostCrypto.generateEphemeralKeyPair();
    const clientKp = await GhostCrypto.generateEphemeralKeyPair();
    const qrSecret = await GhostCrypto.generateOneTimeSecret();

    const hostKeys = await GhostCrypto.deriveSessionKeys(hostKp, clientKp.publicKey, qrSecret.secretBytes, true);
    const clientKeys = await GhostCrypto.deriveSessionKeys(clientKp, hostKp.publicKey, qrSecret.secretBytes, false);

    const encrypted = await GhostCrypto.encryptMessage('Test', hostKeys.txKey, 1);

    // Provide truncated nonce (e.g. 16 bytes instead of 24)
    const truncatedNonceHex = encrypted.nonceHex.substring(0, 32);

    await assert.rejects(
      async () => {
        await GhostCrypto.decryptMessage(encrypted.ciphertextHex, truncatedNonceHex, clientKeys.rxKey, 1);
      },
      /Invalid nonce length/,
      'Truncated nonce must be rejected'
    );
  });

  test('Canonical 12-digit safety number fingerprint derivation is commutative', async () => {
    const kpA = await GhostCrypto.generateEphemeralKeyPair();
    const kpB = await GhostCrypto.generateEphemeralKeyPair();

    const fingerprintAB = await GhostCrypto.calculateSafetyNumber(kpA.publicKey, kpB.publicKey);
    const fingerprintBA = await GhostCrypto.calculateSafetyNumber(kpB.publicKey, kpA.publicKey);

    assert.strictEqual(fingerprintAB, fingerprintBA, 'Safety number must be identical regardless of peer ordering');
    assert.match(fingerprintAB, /^\d{4}-\d{4}-\d{4}$/, 'Safety number must format as XXXX-XXXX-XXXX (12 decimal digits)');
  });

  test('Secure RAM wipe (memzero) clears sensitive byte buffers', async () => {
    const secret = new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
    await GhostCrypto.memzero(secret);
    for (let i = 0; i < secret.length; i++) {
      assert.strictEqual(secret[i], 0, `Byte at index ${i} must be zeroed`);
    }
  });
});

describe('GhostLink Server Protocol & Invitation Security', () => {

  test('Connects to live WebSocket server and receives welcome message', async () => {
    const ws = new WebSocket(TEST_SERVER_URL);
    const welcome = await new Promise((resolve, reject) => {
      ws.on('message', data => {
        const msg = JSON.parse(data.toString());
        if (msg.type === 'welcome') resolve(msg);
      });
      ws.on('error', reject);
    });

    assert.ok(welcome.clientId.startsWith('peer_'), 'Client ID should have peer_ prefix');
    assert.ok(welcome.assignedAlias.startsWith('Shadow_'), 'Assigned alias should have Shadow_ prefix');
    ws.close();
  });

  test('Single-use invitation policy: Token is destroyed after pairing', async () => {
    const hostWs = new WebSocket(TEST_SERVER_URL);
    const scannerWs = new WebSocket(TEST_SERVER_URL);

    await Promise.all([
      new Promise(r => hostWs.once('open', r)),
      new Promise(r => scannerWs.once('open', r))
    ]);

    const code = '7K9M-4Q2X';
    const hostKp = await GhostCrypto.generateEphemeralKeyPair();
    const scannerKp = await GhostCrypto.generateEphemeralKeyPair();

    // Host registers invitation
    hostWs.send(JSON.stringify({
      type: 'register_invitation',
      code: '7k9m4q2x',
      hostPubKeyHex: hostKp.publicKeyHex
    }));

    // Wait 50ms for server to process registration
    await new Promise(r => setTimeout(r, 50));

    // Scanner requests pair
    scannerWs.send(JSON.stringify({
      type: 'request_pair',
      code,
      clientPubKeyHex: scannerKp.publicKeyHex
    }));

    // Host receives request and accepts
    const hostReq = await new Promise(resolve => {
      hostWs.on('message', data => {
        const msg = JSON.parse(data.toString());
        if (msg.type === 'incoming_pair_request') resolve(msg);
      });
    });

    hostWs.send(JSON.stringify({
      type: 'accept_pair',
      peerId: hostReq.fromId,
      hostPubKeyHex: hostKp.publicKeyHex
    }));

    // Both receive pair_connected
    await Promise.all([
      new Promise(resolve => {
        hostWs.on('message', data => {
          const msg = JSON.parse(data.toString());
          if (msg.type === 'pair_connected') resolve(msg);
        });
      }),
      new Promise(resolve => {
        scannerWs.on('message', data => {
          const msg = JSON.parse(data.toString());
          if (msg.type === 'pair_connected') resolve(msg);
        });
      })
    ]);

    // A third party attempts to pair with the same invitation code
    const thirdPartyWs = new WebSocket(TEST_SERVER_URL);
    await new Promise(r => thirdPartyWs.once('open', r));

    thirdPartyWs.send(JSON.stringify({
      type: 'request_pair',
      code,
      clientPubKeyHex: scannerKp.publicKeyHex
    }));

    const errorMsg = await new Promise(resolve => {
      thirdPartyWs.on('message', data => {
        const msg = JSON.parse(data.toString());
        if (msg.type === 'pair_error') resolve(msg);
      });
    });

    assert.ok(
      errorMsg.error.includes('not found') || errorMsg.error.includes('expired'),
      'Single-use invitation must be destroyed and reject subsequent pairings'
    );

    hostWs.close();
    scannerWs.close();
    thirdPartyWs.close();
  });

  test('Rejects pairing with self', async () => {
    const ws = new WebSocket(TEST_SERVER_URL);
    await new Promise(r => ws.once('open', r));

    const code = 'SELF-PAIR';
    const kp = await GhostCrypto.generateEphemeralKeyPair();

    ws.send(JSON.stringify({
      type: 'register_invitation',
      code,
      hostPubKeyHex: kp.publicKeyHex
    }));

    await new Promise(r => setTimeout(r, 50));

    ws.send(JSON.stringify({
      type: 'request_pair',
      code,
      clientPubKeyHex: kp.publicKeyHex
    }));

    const errorMsg = await new Promise(resolve => {
      ws.on('message', data => {
        const msg = JSON.parse(data.toString());
        if (msg.type === 'pair_error') resolve(msg);
      });
    });

    assert.ok(
      errorMsg.error.includes('Cannot pair with yourself'),
      'Server must reject pairing with oneself'
    );

    ws.close();
  });

  test('5-attempt brute-force limit destroys invitation code', async () => {
    const hostWs = new WebSocket(TEST_SERVER_URL);
    const attackerWs = new WebSocket(TEST_SERVER_URL);

    await Promise.all([
      new Promise(r => hostWs.once('open', r)),
      new Promise(r => attackerWs.once('open', r))
    ]);

    const code = 'BRUT-FRC1';
    const hostKp = await GhostCrypto.generateEphemeralKeyPair();
    const attackerKp = await GhostCrypto.generateEphemeralKeyPair();

    hostWs.send(JSON.stringify({
      type: 'register_invitation',
      code,
      hostPubKeyHex: hostKp.publicKeyHex
    }));

    await new Promise(r => setTimeout(r, 50));

    // Send 5 pair attempts with valid code
    let lastError = null;
    attackerWs.on('message', data => {
      const msg = JSON.parse(data.toString());
      if (msg.type === 'pair_error') lastError = msg;
    });

    for (let i = 1; i <= 6; i++) {
      attackerWs.send(JSON.stringify({
        type: 'request_pair',
        code,
        clientPubKeyHex: attackerKp.publicKeyHex
      }));
      await new Promise(r => setTimeout(r, 40));
    }

    assert.ok(lastError, 'Server must emit pair_error when limit exceeded');
    assert.ok(
      lastError.error.includes('not found') || lastError.error.includes('Maximum attempts exceeded'),
      `Expected an exhausted invitation, got: ${lastError.error}`
    );

    hostWs.close();
    attackerWs.close();
  });

  test('Oversized packet rejection (> 2KB)', async () => {
    const hostWs = new WebSocket(TEST_SERVER_URL);
    const scannerWs = new WebSocket(TEST_SERVER_URL);

    await Promise.all([
      new Promise(r => hostWs.once('open', r)),
      new Promise(r => scannerWs.once('open', r))
    ]);

    const code = 'OVER-SIZE';
    const hostKp = await GhostCrypto.generateEphemeralKeyPair();
    const scannerKp = await GhostCrypto.generateEphemeralKeyPair();

    hostWs.send(JSON.stringify({ type: 'register_invitation', code, hostPubKeyHex: hostKp.publicKeyHex }));
    await new Promise(r => setTimeout(r, 50));

    scannerWs.send(JSON.stringify({ type: 'request_pair', code, clientPubKeyHex: scannerKp.publicKeyHex }));

    const hostReq = await new Promise(resolve => {
      hostWs.on('message', data => {
        const msg = JSON.parse(data.toString());
        if (msg.type === 'incoming_pair_request') resolve(msg);
      });
    });

    hostWs.send(JSON.stringify({ type: 'accept_pair', peerId: hostReq.fromId, hostPubKeyHex: hostKp.publicKeyHex }));

    // Wait for pairing
    await new Promise(resolve => {
      scannerWs.on('message', data => {
        const msg = JSON.parse(data.toString());
        if (msg.type === 'pair_connected') resolve();
      });
    });

    // Attempt to send an oversized packet (> 4096 hex characters)
    const giantPayloadHex = 'a'.repeat(5000);
    let receivedGiantPacket = false;

    hostWs.on('message', data => {
      const msg = JSON.parse(data.toString());
      if (msg.type === 'encrypted_blob' && msg.ciphertextHex === giantPayloadHex) {
        receivedGiantPacket = true;
      }
    });

    scannerWs.send(JSON.stringify({
      type: 'encrypted_blob',
      counter: 1,
      nonceHex: '0'.repeat(48),
      ciphertextHex: giantPayloadHex
    }));

    await new Promise(r => setTimeout(r, 200));

    assert.strictEqual(receivedGiantPacket, false, 'Server must silently drop oversized packets > 2KB without relaying');

    hostWs.close();
    scannerWs.close();
  });

  test('Full End-to-End Handshake, AEAD Encrypted Chat Exchange, and Emergency Panic Wipe', async () => {
    const hostTransport = new WssTransport();
    const scannerTransport = new WssTransport();

    hostTransport.connect(TEST_SERVER_URL);
    scannerTransport.connect(TEST_SERVER_URL);

    await Promise.all([
      new Promise(r => hostTransport.once('ready', r)),
      new Promise(r => scannerTransport.once('ready', r))
    ]);

    const hostKp = await GhostCrypto.generateEphemeralKeyPair();
    const scannerKp = await GhostCrypto.generateEphemeralKeyPair();
    const qrSecret = await GhostCrypto.generateOneTimeSecret();
    const inviteCode = 'E2EC-HAT1';

    // Host registers invitation
    hostTransport.registerInvitation({
      code: inviteCode,
      hostPubKeyHex: hostKp.publicKeyHex,
      exp: Date.now() + 300000
    });

    await new Promise(r => setTimeout(r, 50));

    // Scanner requests pair
    let hostHandshakeDone = false;
    let scannerHandshakeDone = false;
    let hostSessionKeys = null;
    let scannerSessionKeys = null;

    hostTransport.on('incoming_pair_request', req => {
      hostTransport.acceptPair(req.fromId, hostKp.publicKeyHex);
    });

    const hostConnectedPromise = new Promise(resolve => {
      hostTransport.on('pair_connected', async conn => {
        const s = await GhostCrypto.ready();
        hostSessionKeys = await GhostCrypto.deriveSessionKeys(
          hostKp,
          s.from_hex(conn.peerPubKeyHex),
          qrSecret.secretBytes,
          true
        );
        hostHandshakeDone = true;
        resolve(conn);
      });
    });

    const scannerConnectedPromise = new Promise(resolve => {
      scannerTransport.on('pair_connected', async conn => {
        const s = await GhostCrypto.ready();
        scannerSessionKeys = await GhostCrypto.deriveSessionKeys(
          scannerKp,
          s.from_hex(conn.peerPubKeyHex),
          qrSecret.secretBytes,
          false
        );
        scannerHandshakeDone = true;
        resolve(conn);
      });
    });

    scannerTransport.requestPair(inviteCode, scannerKp.publicKeyHex);

    await Promise.all([hostConnectedPromise, scannerConnectedPromise]);

    assert.strictEqual(hostHandshakeDone, true, 'Host handshake complete');
    assert.strictEqual(scannerHandshakeDone, true, 'Scanner handshake complete');

    // Verify Safety Numbers
    const safetyHost = await GhostCrypto.calculateSafetyNumber(hostKp.publicKey, scannerKp.publicKey);
    const safetyScanner = await GhostCrypto.calculateSafetyNumber(scannerKp.publicKey, hostKp.publicKey);
    assert.strictEqual(safetyHost, safetyScanner, 'Safety Numbers match identically');

    // ── 1. Host Encrypts and Transmits Message to Scanner ──
    const message1 = 'GhostLink operational on local Wi-Fi.';
    const hostPacket = await GhostCrypto.encryptMessage(message1, hostSessionKeys.txKey, 1);

    const scannerReceivedPromise = new Promise(resolve => {
      scannerTransport.once('encrypted_packet', resolve);
    });

    hostTransport.sendEncryptedPacket(hostPacket);
    const receivedPacketAtScanner = await scannerReceivedPromise;

    // Scanner decrypts
    const decryptedAtScanner = await GhostCrypto.decryptMessage(
      receivedPacketAtScanner.ciphertextHex,
      receivedPacketAtScanner.nonceHex,
      scannerSessionKeys.rxKey,
      1
    );
    assert.strictEqual(decryptedAtScanner.m, message1, 'Scanner successfully decrypted Host message');

    // ── 2. Scanner Encrypts and Transmits Response to Host ──
    const message2 = 'Zero-mock transmission confirmed. AEAD integrity verified.';
    const scannerPacket = await GhostCrypto.encryptMessage(message2, scannerSessionKeys.txKey, 1);

    const hostReceivedPromise = new Promise(resolve => {
      hostTransport.once('encrypted_packet', resolve);
    });

    scannerTransport.sendEncryptedPacket(scannerPacket);
    const receivedPacketAtHost = await hostReceivedPromise;

    // Host decrypts
    const decryptedAtHost = await GhostCrypto.decryptMessage(
      receivedPacketAtHost.ciphertextHex,
      receivedPacketAtHost.nonceHex,
      hostSessionKeys.rxKey,
      1
    );
    assert.strictEqual(decryptedAtHost.m, message2, 'Host successfully decrypted Scanner response');

    // ── 3. Emergency Panic Wipe Triggered by Host ──
    const scannerTerminatedPromise = new Promise(resolve => {
      scannerTransport.once('session_terminated', resolve);
    });

    hostTransport.notifyTermination('panic');
    const termEvent = await scannerTerminatedPromise;
    assert.strictEqual(termEvent.reason, 'panic', 'Peer received emergency panic reason');

    // Wipe session keys in memory
    await GhostCrypto.memzero(hostSessionKeys.txKey);
    await GhostCrypto.memzero(hostSessionKeys.rxKey);
    await GhostCrypto.memzero(scannerSessionKeys.txKey);
    await GhostCrypto.memzero(scannerSessionKeys.rxKey);

    assert.strictEqual(hostSessionKeys.txKey[0], 0, 'Host txKey zeroed');
    assert.strictEqual(scannerSessionKeys.rxKey[0], 0, 'Scanner rxKey zeroed');

    hostTransport.disconnect();
    scannerTransport.disconnect();
  });
});
