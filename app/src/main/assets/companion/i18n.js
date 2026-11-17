'use strict';
globalThis.tr = function(source, ...values) {
  const text = globalThis.ConversateLocale?.messages?.[source] ?? source;
  return text.replace(/\{(\d+)}/g, (token, index) => index < values.length ? String(values[index]) : token);
};
function applyLocale() {
  document.documentElement.lang = globalThis.ConversateLocale?.tag || 'en';
  document.documentElement.dir = /^(ar|fa|he|ur)(-|$)/i.test(document.documentElement.lang) ? 'rtl' : 'ltr';
  document.querySelectorAll('[data-i18n]').forEach(el => { el.textContent = tr(el.dataset.i18n); });
  for (const attr of ['placeholder', 'aria-label']) {
    document.querySelectorAll(`[data-i18n-${attr}]`).forEach(el => { el.setAttribute(attr, tr(el.getAttribute(`data-i18n-${attr}`))); });
  }
}
document.addEventListener('DOMContentLoaded', applyLocale);
// Update app-owned static labels without traversing or translating user content.
setInterval(async () => {
  try {
    const response = await fetch('/locale', {cache:'no-store'});
    if (!response.ok) return;
    const next = await response.json();
    if (next.tag !== globalThis.ConversateLocale?.tag || next.revision !== globalThis.ConversateLocale?.revision) {
      globalThis.ConversateLocale = next;
      applyLocale();
      globalThis.refreshLocaleLabels?.();
    }
  } catch (_) { /* Existing connection status handles network errors. */ }
}, 3000);
