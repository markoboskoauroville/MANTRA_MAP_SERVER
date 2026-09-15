# HANDOFF

**What this is.** An Android app that serves mapsforge maps as HTTP tiles, so MANTRA_TRAIL and
anything else on the network can read every map the same way. Kotlin, Compose, mapsforge 0.25.0,
no web framework: the whole of the HTTP parsing is in `Http.kt` where Test 1 attacks it.

**The floor.** Copied from MANTRA_TRAIL on 15.9.2026, which is the newest app that has it: one
version number in `gradle.properties`, blocking Lint, `allWarningsAsErrors`, the nine gates in
`.github/workflows/build-apk.yml`, `scripts/verify.py`, CI builds only.

**The signing key.** Made in the sandbox, not on the phone, and it lives only in the repository
secrets `SERVER_KEYSTORE` and `SERVER_KEYSTORE_PASSWORD`. android-app.md 3 wants the phone to hold
a copy so it can rotate; that has not been done. Doing it later costs one uninstall, and this app
stores nothing that an uninstall would lose except rendered tiles, which re-render.

**What is tested and what is not.**

- Test 1: 31 cases, all on a desk. Routing, path climbing, tile bounds, malformed request lines,
  the caller table, the status JSON.
- NOT TESTED: nothing has run on a phone. No tile has been served to a real client, no download
  has been run to completion, and the foreground service has never been backgrounded.
- NOT TESTED: rendering on Android. The identical mapsforge call sequence was proved on a desk
  (MANTRA_TRAIL `tools/RenderProbe.java`, 15.9.2026, renders the Croatia file at every zoom from
  14 to 21), but `AndroidGraphicFactory` is a different graphics implementation from the AWT one.

**The first thing to check on the phone**: start serving, then open `http://127.0.0.1:8088/` in a
browser on the phone. If the page lists a map, the server is up. Then fetch one tile.
