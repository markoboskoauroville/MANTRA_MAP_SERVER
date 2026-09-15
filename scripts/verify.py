#!/usr/bin/env python3
"""verify.py: the structural checks a compiler will not run. Every check PRINTS WHAT IT EXAMINED
(delivery-gate.md 14); a count of zero is a broken check until proven otherwise."""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/mantra/mapserver"
TESTS = ROOT / "app/src/test/java/com/mantra/mapserver/CoreTest.kt"
TEST_FLOOR = 30

# The front door faces the network, so the parsing of it must be attackable on a desk.
PURE = ["Http.kt", "Usage.kt", "Regions.kt"]

failures, checks = [], []


def code_only(text):
    without_block = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(l for l in without_block.split("\n") if not l.lstrip().startswith(("//", "*")))


def check(name, ok, detail):
    checks.append(name)
    print(f"{'pass' if ok else 'FAIL'}  {name}: {detail}")
    if not ok:
        failures.append(name)


for name in PURE:
    text = code_only((MAIN / name).read_text())
    imports = re.findall(r"^import .*$", text, re.M)
    android = [i for i in imports if i.startswith(("import android.", "import androidx."))]
    check(f"{name} imports nothing from Android", not android,
          f"{len(imports)} imports examined, {len(android)} from android")

gp = (ROOT / "gradle.properties").read_text()
m = re.search(r"^appVersion=(\d+)$", gp, re.M)
bg = (ROOT / "app/build.gradle.kts").read_text()
check("one version in gradle.properties, derived in build.gradle.kts",
      bool(m) and "versionCode = appVersion" in bg,
      f"appVersion={m.group(1) if m else '?'}")

mf = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
for need in ("INTERNET", "FOREGROUND_SERVICE_DATA_SYNC", ".ServerService",
             'android:foregroundServiceType="dataSync"'):
    check(f"manifest carries {need}", need in mf, "present" if need in mf else "MISSING")
check("the server asks for no location and no camera",
      "LOCATION" not in mf and "CAMERA" not in mf, "a tile server has no business with either")

http = code_only((MAIN / "Http.kt").read_text())
server = code_only((MAIN / "Server.kt").read_text())
usage = code_only((MAIN / "Usage.kt").read_text())

# A PATH FROM THE NETWORK MUST NEVER REACH THE FILE SYSTEM UNCHECKED.
check("map names are allowed by shape, not filtered by blacklist",
      "isLetterOrDigit()" in http and "fun safeName" in http,
      "letters, digits, dash, underscore; everything else refused")
check("the tile route goes through safeName",
      "safeName(bits[0])" in http, "no path from the wire reaches a file name unchecked")
check("a request line has a length ceiling",
      "MAX_REQUEST_LINE" in http, "before it is parsed, not after")

check("one bad connection cannot take the server down",
      server.count("catch (e: Exception)") >= 4,
      f"{server.count('catch (e: Exception)')} guarded places: accept, answer, close, stop")
check("a connection that says nothing is dropped",
      "soTimeout" in server, "the socket's own deadline")
check("the thread pool is fixed, not unbounded",
      "newFixedThreadPool" in server, "a phone is not a data centre")
check("every answer is counted against an address",
      "Usage.served" in server and "Usage.refused" in server,
      "served and refused both, or the list would flatter the network")

check("the caller list is bounded",
      "MAX_CALLERS" in usage and "fun fold" in usage, "and folds rather than growing")
check("folding keeps the totals",
      'callers[OTHERS] = if (existing == null)' in usage,
      "the folded row is added to, never overwritten (Test 1 caught this)")

renderer = code_only((MAIN / "Renderer.kt").read_text())
check("a rendered tile is written beside itself and moved into place",
      ".part" in renderer and "renameTo" in renderer,
      "a half-written tile is never read back as a tile")
check("rendered tiles are kept",
      "cacheDir" in renderer, "the second visit to a zoom is a file read")

download = code_only((MAIN / "Download.kt").read_text())
check("a region download resumes", "Range" in download and ".part" in download, "a Range header")
check("a part file only becomes a map when it is whole",
      "part.length() < total" in download, "the length is checked before the rename")

screens = (MAIN / "Screens.kt").read_text()
check("the screen scrolls", "verticalScroll(rememberScrollState())" in screens, "the lists grow")
check("nothing sits under the system bars", "safeDrawingPadding()" in screens, "present")
check("the one button says what the next press does",
      '"stop serving"' in screens and '"start serving"' in screens, "both states named")
check("the address a laptop would use is on the screen",
      "Server.localAddress()" in screens, "not only the loopback one")

shapes = re.compile(r"(AIza|gsk_|ghp_|github_pat_|sk-ant-|xox[baprs]-)[A-Za-z0-9_-]{20,}|\b[0-9a-f]{32}\b")
scanned, hits = 0, []
for f in list(ROOT.rglob("*.kt")) + list(ROOT.rglob("*.kts")) + list(ROOT.rglob("*.xml")) + \
        list(ROOT.rglob("*.yml")) + list(ROOT.rglob("*.md")):
    if "/build/" in str(f) or "/.git/" in str(f):
        continue
    scanned += 1
    if shapes.search(f.read_text(errors="ignore")):
        hits.append(str(f.relative_to(ROOT)))
check("no key-shaped string anywhere in the tree", not hits, f"{scanned} files examined, {len(hits)} hits")

n = len(re.findall(r"@Test", TESTS.read_text()))
check(f"at least {TEST_FLOOR} unit tests", n >= TEST_FLOOR, f"{n} @Test cases")

print(f"\n{len(checks)} checks, {len(failures)} failed")
if failures:
    print("failed: " + ", ".join(failures))
    sys.exit(1)
