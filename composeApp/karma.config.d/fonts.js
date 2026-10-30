// Serve the real bundled fonts at their production URL so Wasm browser tests can read them.
// KGP's generated Karma config lists no compose resources; without this, every real-byte font
// test (and the dialog/header tests whose theme loads fonts) gets HTTP 404 -> MissingResourceException.
config.set({
  files: [
    ...config.files,
    { pattern: 'kotlin/composeResources/com.techgv.cineverse.ui.resources/font/*.ttf', included: false, served: true, watched: false, nocache: true },
  ],
  proxies: {
    ...config.proxies,
    '/composeResources/com.techgv.cineverse.ui.resources/font/': '/base/kotlin/composeResources/com.techgv.cineverse.ui.resources/font/',
  },
});
