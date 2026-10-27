/* Shared invitation-code format: eight alphanumeric characters, displayed 4-4. */
(function(root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.GhostInvitation = factory();
})(typeof self !== 'undefined' ? self : this, function() {
  'use strict';

  function compact(value) {
    return String(value || '').toUpperCase().replace(/[\s-]/g, '');
  }

  function normalize(value) {
    const code = compact(value);
    return /^[A-Z0-9]{8}$/.test(code) ? `${code.slice(0, 4)}-${code.slice(4)}` : null;
  }

  function formatPartial(value) {
    const code = compact(value);
    if (!/^[A-Z0-9]*$/.test(code)) return String(value || '').toUpperCase();
    return code.length > 4 && code.length <= 8 ? `${code.slice(0, 4)}-${code.slice(4)}` : code;
  }

  return { compact, normalize, formatPartial };
});
