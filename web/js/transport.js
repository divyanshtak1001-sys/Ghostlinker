/**
 * GHOSTLINK PLUGGABLE TRANSPORT ABSTRACTION
 * Decouples transport mechanics (WSS, WebRTC, Bluetooth LE, Wi-Fi Direct, Tor)
 * from application logic and the cryptographic layer.
 */

(function(root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.GhostTransport = factory();
  }
})(typeof self !== 'undefined' ? self : this, function() {
  'use strict';

  class EventEmitter {
    constructor() {
      this.listeners = new Map();
    }
    on(event, fn) {
      if (!this.listeners.has(event)) this.listeners.set(event, []);
      this.listeners.get(event).push(fn);
      return this;
    }
    off(event, fn) {
      if (!this.listeners.has(event)) return this;
      const list = this.listeners.get(event).filter(l => l !== fn);
      this.listeners.set(event, list);
      return this;
    }
    once(event, fn) {
      const g = (...args) => {
        this.off(event, g);
        fn(...args);
      };
      return this.on(event, g);
    }
    emit(event, ...args) {
      const list = this.listeners.get(event) || [];
      for (const fn of list) {
        try { fn(...args); } catch (e) { console.error(`[Transport Error: ${event}]`, e); }
      }
    }
  }

  /**
   * Base Transport Interface
   */
  class ITransport extends EventEmitter {
    connect() { throw new Error('Not implemented'); }
    disconnect() { throw new Error('Not implemented'); }
    publishPresence(alias) { throw new Error('Not implemented'); }
    registerInvitation(inviteData) { throw new Error('Not implemented'); }
    requestPair(code, payload) { throw new Error('Not implemented'); }
    acceptPair(peerId, payload) { throw new Error('Not implemented'); }
    rejectPair(peerId) { throw new Error('Not implemented'); }
    sendEncryptedPacket(packet) { throw new Error('Not implemented'); }
    notifyTermination(reason) { throw new Error('Not implemented'); }
  }

  /**
   * Secure WebSocket / LAN Transport Implementation
   */
  class WssTransport extends ITransport {
    constructor() {
      super();
      this.ws = null;
      this.connected = false;
      this.reconnectTimer = null;
      this.clientId = null;
      this.currentHost = null;
    }

    connect(serverUrl) {
      const WS = typeof WebSocket !== 'undefined' ? WebSocket : (typeof require === 'function' ? require('ws') : null);
      if (!WS) throw new Error('WebSocket implementation not available');

      if (this.ws && (this.ws.readyState === WS.OPEN || this.ws.readyState === WS.CONNECTING)) {
        return;
      }

      let defaultProtocol = 'ws:';
      let defaultHost = 'localhost:8088';
      if (typeof window !== 'undefined' && window.location) {
        defaultProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        defaultHost = window.location.host;
      }

      const targetUrl = serverUrl || `${defaultProtocol}//${defaultHost}`;
      this.currentHost = targetUrl;

      try {
        this.ws = new WS(targetUrl);

        this.ws.onopen = () => {
          this.connected = true;
          this.emit('status_change', { status: 'connected', url: targetUrl });
          if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
        };

        this.ws.onmessage = (evt) => {
          try {
            const dataStr = typeof evt.data === 'string' ? evt.data : evt.data.toString();
            const msg = JSON.parse(dataStr);
            this._handleIncoming(msg);
          } catch (e) {
            console.error('[WssTransport] Malformed packet received:', e);
          }
        };

        this.ws.onclose = () => {
          this.connected = false;
          this.emit('status_change', { status: 'disconnected' });
          if (!this.manualClose) {
            this.reconnectTimer = setTimeout(() => {
              this.connect(this.currentHost);
            }, 2500);
          }
        };

        this.ws.onerror = (err) => {
          this.emit('error', err);
        };
      } catch (e) {
        this.emit('error', e);
      }
    }

    disconnect() {
      this.manualClose = true;
      if (this.reconnectTimer) {
        clearTimeout(this.reconnectTimer);
        this.reconnectTimer = null;
      }
      if (this.ws) {
        try { this.ws.close(); } catch (e) {}
        this.ws = null;
      }
      this.connected = false;
    }

    _send(payload) {
      const WS = typeof WebSocket !== 'undefined' ? WebSocket : (typeof require === 'function' ? require('ws') : null);
      if (this.ws && this.ws.readyState === (WS ? WS.OPEN : 1)) {
        this.ws.send(JSON.stringify(payload));
        return true;
      }
      return false;
    }

    _handleIncoming(msg) {
      switch (msg.type) {
        case 'welcome':
          this.clientId = msg.clientId;
          this.emit('ready', { clientId: msg.clientId, lanIp: msg.lanIp, port: msg.port });
          break;

        case 'peer_list':
          this.emit('peer_list', msg.peers.filter(p => p.id !== this.clientId));
          break;

        case 'invitation_registered':
          this.emit('invitation_registered', { code: msg.code, inviteId: msg.inviteId });
          break;

        case 'incoming_pair_request':
          this.emit('incoming_pair_request', {
            fromId: msg.fromId,
            fromAlias: msg.fromAlias,
            clientPubKeyHex: msg.clientPubKeyHex,
            code: msg.code,
            isTypedCodeJoin: !!msg.isTypedCodeJoin
          });
          break;

        case 'pair_matched':
          this.emit('pair_matched', {
            hostId: msg.hostId,
            hostAlias: msg.hostAlias,
            hostPubKeyHex: msg.hostPubKeyHex,
            code: msg.code,
            isTypedCodeJoin: !!msg.isTypedCodeJoin
          });
          break;

        case 'pair_rejected':
          this.emit('pair_rejected', { fromAlias: msg.fromAlias });
          break;

        case 'pair_connected':
          this.emit('pair_connected', {
            peerId: msg.peerId,
            peerAlias: msg.peerAlias,
            peerPubKeyHex: msg.peerPubKeyHex,
            isHost: msg.isHost,
            isTypedCodeJoin: !!msg.isTypedCodeJoin
          });
          break;

        case 'encrypted_blob':
          this.emit('encrypted_packet', {
            fromId: msg.fromId,
            counter: msg.counter,
            nonceHex: msg.nonceHex,
            ciphertextHex: msg.ciphertextHex,
            timestamp: msg.timestamp
          });
          break;

        case 'session_terminated':
          this.emit('session_terminated', { reason: msg.reason });
          break;

        case 'pair_error':
          this.emit('pair_error', { error: msg.error });
          break;
      }
    }

    publishPresence(alias) {
      this._send({ type: 'presence', alias });
    }

    registerInvitation(inviteData) {
      this._send({
        type: 'register_invitation',
        code: inviteData.code,
        hostPubKeyHex: inviteData.hostPubKeyHex,
        exp: inviteData.exp
      });
    }

    requestPair(code, myPubKeyHex, typedCodeOnly = false) {
      const invitation = (typeof window !== 'undefined' && window.GhostInvitation)
        ? window.GhostInvitation : (typeof require === 'function' ? require('./invitation') : null);
      const normalizedCode = invitation && invitation.normalize(code);
      if (!normalizedCode) {
        this.emit('pair_error', { error: 'Invitation code must contain exactly eight letters or numbers.' });
        return false;
      }
      return this._send({
        type: 'request_pair',
        code: normalizedCode,
        clientPubKeyHex: myPubKeyHex,
        typedCodeOnly: !!typedCodeOnly
      });
    }

    acceptPair(peerId, myPubKeyHex, peerPubKeyHex, isTypedCodeJoin = false) {
      this._send({
        type: 'accept_pair',
        peerId,
        hostPubKeyHex: myPubKeyHex,
        peerPubKeyHex: peerPubKeyHex,
        isTypedCodeJoin: !!isTypedCodeJoin
      });
    }

    rejectPair(peerId) {
      this._send({
        type: 'reject_pair',
        peerId
      });
    }

    sendEncryptedPacket(packet) {
      return this._send({
        type: 'encrypted_blob',
        counter: packet.counter,
        nonceHex: packet.nonceHex,
        ciphertextHex: packet.ciphertextHex,
        timestamp: Date.now()
      });
    }

    notifyTermination(reason) {
      this._send({
        type: 'terminate_session',
        reason: reason || 'exit'
      });
    }
  }

  return {
    ITransport,
    WssTransport
  };
});
