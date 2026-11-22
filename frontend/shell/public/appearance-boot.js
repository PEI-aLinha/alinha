// Applies the stored theme and colour-vision mode before the first paint, so a
// dark-theme user never sees the light theme flash while React loads.
// Loaded as a blocking classic script from index.html (no inline script, so a
// `script-src 'self'` CSP still allows it). AppearanceProvider takes over after
// mount. Keep the keys and values in sync with src/appearance/appearanceModel.ts
// (appearance.test.tsx checks this).
/* global document, localStorage, window */
;(function () {
  var root = document.documentElement
  function read(key, allowed) {
    try {
      var value = localStorage.getItem(key)
      return allowed.indexOf(value) >= 0 ? value : null
    } catch {
      return null
    }
  }
  var theme = read('appearance.theme', ['system', 'light', 'dark']) || 'system'
  if (theme === 'system') {
    var dark = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: dark)').matches
    theme = dark ? 'dark' : 'light'
  }
  root.dataset.theme = theme
  var cvd = read('appearance.cvd', ['protanopia', 'deuteranopia', 'tritanopia'])
  if (cvd) root.dataset.cvd = cvd
})()
