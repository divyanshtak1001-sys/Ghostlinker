/**
 * GHOSTLINK CRYPTOGRAPHIC ENGINE
 * Implemented using libsodium (libsodium-wrappers)
 * Standards:
 * - Key Exchange: X25519 (crypto_kx)
 * - Shared Secret Authentication: HKDF / Blake2b keyed hash over 128-bit single-use token
 * - Authenticated Encryption (AEAD): XChaCha20-Poly1305 with unique 192-bit nonces
 * - Message Padding: Constant 512-byte blocks (anti-traffic analysis)
 * - Safety Number: Sorted canonical public-key fingerprint (12 digits)
 * - Forward Secrecy: Ephemeral in-memory keys, memzero wiped on session end
 */

(function(root, factory) {
  if (typeof module === 'object' && module.exports) {
    // Node.js environment
    const sodium = require('libsodium-wrappers');
    module.exports = factory(sodium);
  } else {
    // Browser environment
    root.GhostCrypto = factory(root.sodium);
  }
})(typeof self !== 'undefined' ? self : this, function(sodiumInstance) {
  'use strict';

  let sodium = sodiumInstance;
  const PAD_SIZE = 512;
  const PROTOCOL_VERSION = 1;

  async function ensureSodium() {
    if (!sodium) {
      if (typeof window !== 'undefined' && window.sodium) {
        sodium = window.sodium;
      } else {
        throw new Error('libsodium is not loaded');
      }
    }
    await sodium.ready;
    return sodium;
  }

  function concatUint8(a, b) {
    const c = new Uint8Array(a.length + b.length);
    c.set(a, 0);
    c.set(b, a.length);
    return c;
  }

  return {
    ready: ensureSodium,
    version: PROTOCOL_VERSION,

    /**
     * Generates a fresh ephemeral X25519 keypair for a session.
     */
    async generateEphemeralKeyPair() {
      const s = await ensureSodium();
      const kp = s.crypto_kx_keypair();
      return {
        publicKey: kp.publicKey,
        privateKey: kp.privateKey,
        publicKeyHex: s.to_hex(kp.publicKey)
      };
    },

    /**
     * Generates a 128-bit (16-byte) cryptographically secure one-time secret.
     */
    async generateOneTimeSecret() {
      const s = await ensureSodium();
      const secretBytes = s.randombytes_buf(16);
      return {
        secretBytes,
        secretHex: s.to_hex(secretBytes)
      };
    },

    /**
     * Derives separate Rx and Tx session keys using X25519 diffie-hellman
     * and authenticates with the one-time QR secret token.
     * 
     * @param {Object} myKeyPair - { publicKey, privateKey }
     * @param {Uint8Array} peerPublicKey - 32-byte peer public key
     * @param {Uint8Array} oneTimeSecret - 16-byte one-time secret from QR
     * @param {boolean} isHost - true if this peer created the invitation
     */
    async deriveSessionKeys(myKeyPair, peerPublicKey, oneTimeSecret, isHost) {
      const s = await ensureSodium();

      if (peerPublicKey.length !== 32) {
        throw new Error('Invalid peer public key length (expected 32 bytes)');
      }
      if (oneTimeSecret.length !== 16) {
        throw new Error('Invalid one-time secret length (expected 16 bytes)');
      }

      // X25519 key exchange
      let kxSession;
      if (isHost) {
        // Host acts as kx server
        kxSession = s.crypto_kx_server_session_keys(myKeyPair.publicKey, myKeyPair.privateKey, peerPublicKey);
      } else {
        // Scanner acts as kx client
        kxSession = s.crypto_kx_client_session_keys(myKeyPair.publicKey, myKeyPair.privateKey, peerPublicKey);
      }

      // Bind shared keys with the one-time secret to authenticate possession of QR
      let txKey, rxKey;
      if (isHost) {
        // Host transmits on HOST_TO_CLIENT, receives on CLIENT_TO_HOST
        const txAuthInput = concatUint8(kxSession.sharedTx, oneTimeSecret);
        const rxAuthInput = concatUint8(kxSession.sharedRx, oneTimeSecret);
        txKey = s.crypto_generichash(32, txAuthInput, s.from_string('HOST_TO_CLIENT_V1'));
        rxKey = s.crypto_generichash(32, rxAuthInput, s.from_string('CLIENT_TO_HOST_V1'));
      } else {
        // Client transmits on CLIENT_TO_HOST, receives on HOST_TO_CLIENT
        const txAuthInput = concatUint8(kxSession.sharedTx, oneTimeSecret);
        const rxAuthInput = concatUint8(kxSession.sharedRx, oneTimeSecret);
        txKey = s.crypto_generichash(32, txAuthInput, s.from_string('CLIENT_TO_HOST_V1'));
        rxKey = s.crypto_generichash(32, rxAuthInput, s.from_string('HOST_TO_CLIENT_V1'));
      }

      // Wipe raw kx session keys from memory
      s.memzero(kxSession.sharedTx);
      s.memzero(kxSession.sharedRx);

      return {
        txKey,
        rxKey
      };
    },

    /**
     * Typed-code sessions deliberately do not use the code as key material.
     * A relay cannot calculate these X25519-derived keys without an ephemeral
     * private key. Safety-number verification remains mandatory because an
     * active relay could substitute public keys.
     */
    async deriveTypedCodeSessionKeys(myKeyPair, peerPublicKey, isHost) {
      const s = await ensureSodium();
      if (peerPublicKey.length !== 32) throw new Error('Invalid peer public key length (expected 32 bytes)');
      const kx = isHost
        ? s.crypto_kx_server_session_keys(myKeyPair.publicKey, myKeyPair.privateKey, peerPublicKey)
        : s.crypto_kx_client_session_keys(myKeyPair.publicKey, myKeyPair.privateKey, peerPublicKey);
      const context = s.from_string('GHOSTLINK_TYPED_X25519_V1');
      const txKey = s.crypto_generichash(32, kx.sharedTx, context);
      const rxKey = s.crypto_generichash(32, kx.sharedRx, context);
      s.memzero(kx.sharedTx);
      s.memzero(kx.sharedRx);
      return { txKey, rxKey };
    },

    /**
     * Encrypts a message using XChaCha20-Poly1305 AEAD with constant padding.
     * 
     * @param {string} plaintext - Plaintext message
     * @param {Uint8Array} txKey - 32-byte transmission key
     * @param {number} counter - Monotonic counter for replay prevention
     */
    async encryptMessage(plaintext, txKey, counter) {
      const s = await ensureSodium();

      // Encode payload to JSON with metadata (timestamp, counter)
      const payloadObj = {
        c: counter,
        t: Date.now(),
        m: plaintext
      };
      const rawBytes = s.from_string(JSON.stringify(payloadObj));

      if (rawBytes.length > PAD_SIZE - 64) {
        throw new Error(`Message exceeds maximum allowable length (${PAD_SIZE - 64} bytes)`);
      }

      // Constant block padding
      const paddedBytes = s.pad(rawBytes, PAD_SIZE);

      // Generate 24-byte unique nonce for XChaCha20
      const nonce = s.randombytes_buf(s.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES);

      // Additional Authenticated Data (AAD): protocol version and counter
      const aad = s.from_string(`v${PROTOCOL_VERSION}:${counter}`);

      const ciphertext = s.crypto_aead_xchacha20poly1305_ietf_encrypt(
        paddedBytes,
        aad,
        null,
        nonce,
        txKey
      );

      return {
        counter,
        nonceHex: s.to_hex(nonce),
        ciphertextHex: s.to_hex(ciphertext)
      };
    },

    /**
     * Decrypts and verifies an XChaCha20-Poly1305 AEAD message.
     * 
     * @param {string} ciphertextHex - Hex encoded ciphertext + auth tag
     * @param {string} nonceHex - Hex encoded 24-byte nonce
     * @param {Uint8Array} rxKey - 32-byte reception key
     * @param {number} expectedCounter - Expected counter to verify
     */
    async decryptMessage(ciphertextHex, nonceHex, rxKey, expectedCounter) {
      const s = await ensureSodium();

      const ciphertext = s.from_hex(ciphertextHex);
      const nonce = s.from_hex(nonceHex);

      if (nonce.length !== s.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES) {
        throw new Error('Invalid nonce length');
      }

      const aad = s.from_string(`v${PROTOCOL_VERSION}:${expectedCounter}`);

      let decryptedPadded;
      try {
        decryptedPadded = s.crypto_aead_xchacha20poly1305_ietf_decrypt(
          null,
          ciphertext,
          aad,
          nonce,
          rxKey
        );
      } catch (e) {
        throw new Error('Ciphertext integrity check failed (corrupted or tampered packet)');
      }

      // Remove constant padding
      const unpadded = s.unpad(decryptedPadded, PAD_SIZE);
      const jsonStr = s.to_string(unpadded);
      const payload = JSON.parse(jsonStr);

      if (payload.c !== expectedCounter) {
        throw new Error(`Counter mismatch: expected ${expectedCounter}, got ${payload.c}`);
      }

      return payload;
    },

    /**
     * Calculates a 12-digit safety number fingerprint from two public keys.
     * Canonical ordering ensures both peers see the exact same fingerprint.
     */
    async calculateSafetyNumber(pubKeyA, pubKeyB) {
      const s = await ensureSodium();

      const hexA = s.to_hex(pubKeyA);
      const hexB = s.to_hex(pubKeyB);

      // Canonical sort
      const [first, second] = hexA < hexB ? [pubKeyA, pubKeyB] : [pubKeyB, pubKeyA];
      const combined = concatUint8(first, second);
      const hash = s.crypto_generichash(32, combined, s.from_string('GHOSTLINK_SAFETY_FINGERPRINT'));

      // Convert first 6 bytes into 12 decimal digits (grouped in 3x 4 digits)
      let numStr = '';
      for (let i = 0; i < 6; i++) {
        numStr += hash[i].toString().padStart(2, '0');
      }

      return `${numStr.substring(0, 4)}-${numStr.substring(4, 8)}-${numStr.substring(8, 12)}`;
    },

    /**
     * Securely clears sensitive buffers from memory.
     */
    async memzero(buf) {
      const s = await ensureSodium();
      if (buf && buf.length) {
        s.memzero(buf);
      }
    }
  };
});
