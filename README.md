# MANTRA MAP SERVER

Serves mapsforge offline maps as ordinary z/x/y PNG tiles, over HTTP, from the phone.

    http://127.0.0.1:8088/tiles/croatia/{z}/{x}/{y}.png

**Why.** Baba, 15.9.2026: the offline map and Thunderforest should reach a map app the same way —
through a URL. So the rendering happens here, once, and the result is kept as a PNG. MANTRA_TRAIL
then has one code path for every map instead of two, and any other device on the same wifi can use
the same maps: a laptop, a tablet, another phone.

**What it does**

- renders mapsforge `.map` files into 256 px PNG tiles, and keeps every tile it renders
- downloads a region from mapsforge's own server, resumably, with the size said first
- takes a `.map` file from the phone with the file picker and copies it in
- counts every tile served, by the address that asked for it, and shows the table
- serves `/`, `/maps`, `/status` for a browser on another machine

**What it does not do**

- no authentication. Anything on the same network can fetch tiles. That is the feature and the
  risk both; the caller table is how you see who is using it.
- no HTTPS. It is a phone serving its own files on a local network.
- Google and Thunderforest tiles are not served here and never will be: their terms forbid
  storing their tiles, and a server is storage with a door on it.

**The routes**

| route | what comes back |
|---|---|
| `/tiles/{map}/{z}/{x}/{y}.png` | a rendered tile, or 204 when the map has nothing there |
| `/maps` | the maps being served, with their URL templates |
| `/status` | uptime, tiles served, and every caller |
| `/` | a page a browser can read |

Built by CI, never on a desk (MANTRA_MANIFEST/modules/android-app.md 1a). The APK of every
version is on the releases page.
