package com.mantra.mapserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TEST 1 — THE MECHANISM, ALONE (four-tests.md).
 *
 * The front door of this app faces the network, so most of these cases are things somebody sends
 * that are not a tile request: a path that tries to climb out of the maps folder, a zoom that is
 * not a number, a tile off the edge of the world, a request line with no method in it. Each one
 * must come back as a refusal with a reason, never as an exception and never as a file.
 */
class CoreTest {

    @Before fun clean() {
        Usage.forget()
    }

    // --- the front door ------------------------------------------------------------------------

    @Test fun anOrdinaryTileRequestIsUnderstood() {
        val route = Http.route("GET /tiles/croatia/12/2229/1460.png HTTP/1.1")
        assertTrue(route is Http.Route.Tile)
        val tile = (route as Http.Route.Tile).request
        assertEquals("croatia", tile.map)
        assertEquals(12, tile.zoom)
        assertEquals(2229, tile.x)
        assertEquals(1460, tile.y)
    }

    @Test fun theExtensionIsOptional() {
        assertTrue(Http.route("GET /tiles/croatia/12/2229/1460 HTTP/1.1") is Http.Route.Tile)
    }

    @Test fun aQueryStringIsIgnoredRatherThanRefused() {
        // Map apps add their own parameters. They are not ours to mind.
        val route = Http.route("GET /tiles/croatia/12/2229/1460.png?apikey=whatever HTTP/1.1")
        assertTrue(route is Http.Route.Tile)
    }

    @Test fun headIsAnsweredLikeGet() {
        assertTrue(Http.route("HEAD /tiles/croatia/12/2229/1460.png HTTP/1.1") is Http.Route.Tile)
    }

    @Test fun theOtherMethodsAreRefusedWithTheReason() {
        listOf("POST", "PUT", "DELETE", "PATCH", "TRACE").forEach {
            val route = Http.route("$it /tiles/croatia/12/2229/1460.png HTTP/1.1")
            assertEquals(it, 405, (route as Http.Route.Refused).code)
        }
    }

    @Test fun theThreePagesAreRouted() {
        assertEquals(Http.Route.Index, Http.route("GET / HTTP/1.1"))
        assertEquals(Http.Route.Maps, Http.route("GET /maps HTTP/1.1"))
        assertEquals(Http.Route.Status, Http.route("GET /status HTTP/1.1"))
    }

    @Test fun anythingElseIsAFourZeroFour() {
        assertEquals(404, (Http.route("GET /secrets HTTP/1.1") as Http.Route.Refused).code)
    }

    // --- the cases that are somebody trying it on ----------------------------------------------

    @Test fun aPathCannotClimbOutOfTheMapsFolder() {
        listOf(
            "GET /tiles/../../../etc/passwd/12/1/1.png HTTP/1.1",
            "GET /tiles/..%2f..%2fetc/12/1/1.png HTTP/1.1",
            "GET /tiles/a\\b/12/1/1.png HTTP/1.1",
            "GET /tiles/./12/1/1.png HTTP/1.1",
        ).forEach {
            val route = Http.route(it)
            assertFalse(it, route is Http.Route.Tile)
        }
    }

    @Test fun aMapNameIsLettersDigitsDashAndUnderscoreAndNothingElse() {
        assertNotNull(Http.safeName("croatia"))
        assertNotNull(Http.safeName("croatia_2026"))
        assertNotNull(Http.safeName("balkan-oam"))
        assertNull(Http.safeName("../etc"))
        assertNull(Http.safeName("a/b"))
        assertNull(Http.safeName("a b"))
        assertNull(Http.safeName(""))
        assertNull(Http.safeName("x".repeat(65)))
    }

    @Test fun aZoomThatIsNotANumberIsRefused() {
        assertEquals(400, (Http.route("GET /tiles/croatia/twelve/1/1.png HTTP/1.1") as Http.Route.Refused).code)
    }

    @Test fun aTileOffTheEdgeOfTheWorldIsRefused() {
        // At zoom 2 there are four tiles each way, numbered 0 to 3.
        assertEquals(404, (Http.route("GET /tiles/croatia/2/4/0.png HTTP/1.1") as Http.Route.Refused).code)
        assertEquals(404, (Http.route("GET /tiles/croatia/2/0/9.png HTTP/1.1") as Http.Route.Refused).code)
        assertTrue(Http.route("GET /tiles/croatia/2/3/3.png HTTP/1.1") is Http.Route.Tile)
    }

    @Test fun aNegativeTileIsRefused() {
        assertEquals(400, (Http.route("GET /tiles/croatia/2/-1/0.png HTTP/1.1") as Http.Route.Refused).code)
    }

    @Test fun anImpossibleZoomIsRefused() {
        assertEquals(400, (Http.route("GET /tiles/croatia/40/1/1.png HTTP/1.1") as Http.Route.Refused).code)
    }

    @Test fun aRequestLineWithNothingInItIsRefusedRatherThanThrown() {
        assertEquals(400, (Http.route("") as Http.Route.Refused).code)
        assertEquals(400, (Http.route("GET") as Http.Route.Refused).code)
        assertEquals(400, (Http.route("\n") as Http.Route.Refused).code)
    }

    @Test fun anEnormousRequestLineIsRefusedBeforeItIsParsed() {
        val huge = "GET /tiles/" + "a".repeat(5000) + "/1/1/1.png HTTP/1.1"
        assertEquals(414, (Http.route(huge) as Http.Route.Refused).code)
    }

    @Test fun tooFewPartsInATilePathIsRefused() {
        assertEquals(400, (Http.route("GET /tiles/croatia/12/1.png HTTP/1.1") as Http.Route.Refused).code)
        assertEquals(400, (Http.route("GET /tiles/croatia/12/1/1/1.png HTTP/1.1") as Http.Route.Refused).code)
    }

    // --- what goes back down the wire -----------------------------------------------------------

    @Test fun aHeaderSaysTheLengthAndTheType() {
        val head = Http.header(200, "image/png", 4096)
        assertTrue(head.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(head.contains("Content-Type: image/png\r\n"))
        assertTrue(head.contains("Content-Length: 4096\r\n"))
        assertTrue(head.endsWith("\r\n\r\n"))
    }

    @Test fun aTileMayBeCachedHardAndAnErrorMayNot() {
        assertTrue(Http.header(200, "image/png", 10, cacheSeconds = 86_400).contains("max-age=86400"))
        assertFalse(Http.header(404, "text/plain", 10).contains("Cache-Control"))
    }

    @Test fun everyCodeUsedHasAReasonPhrase() {
        listOf(200, 204, 400, 404, 405, 414, 500, 503).forEach {
            assertFalse(it.toString(), Http.statusText(it) == "Unknown")
        }
    }

    @Test fun theTemplateIsTheOneAMapAppCanUse() {
        assertEquals(
            "http://127.0.0.1:8088/tiles/croatia/{z}/{x}/{y}.png",
            Http.tileTemplate("127.0.0.1", 8088, "croatia"),
        )
    }

    // --- who asked for what ---------------------------------------------------------------------

    @Test fun nobodyHasAskedForAnythingYet() {
        assertEquals(0, Usage.totalTiles())
        assertTrue(Usage.callers().isEmpty())
    }

    @Test fun oneCallerIsCountedWithItsBytes() {
        Usage.served("192.168.1.5", 40_000, 1_000)
        Usage.served("192.168.1.5", 20_000, 2_000)
        val caller = Usage.callers().single()
        assertEquals("192.168.1.5", caller.address)
        assertEquals(2, caller.tiles)
        assertEquals(60_000L, caller.bytes)
        assertEquals(1_000L, caller.firstMs)
        assertEquals(2_000L, caller.lastMs)
    }

    @Test fun refusalsAreCountedButAreNotTiles() {
        Usage.served("10.0.0.2", 1_000, 1)
        Usage.refused("10.0.0.2", 2)
        val caller = Usage.callers().single()
        assertEquals(1, caller.tiles)
        assertEquals(1, caller.misses)
        assertEquals(1_000L, caller.bytes)
    }

    @Test fun theBusiestCallerIsFirst() {
        Usage.served("a", 1, 1)
        repeat(5) { Usage.served("b", 1, 2) }
        repeat(3) { Usage.served("c", 1, 3) }
        assertEquals(listOf("b", "c", "a"), Usage.callers().map { it.address })
    }

    @Test fun aCrowdedNetworkFoldsIntoOneRowRatherThanGrowingForever() {
        repeat(Usage.MAX_CALLERS * 3) { Usage.served("10.0.0.$it", 1_000, it.toLong()) }
        val callers = Usage.callers()
        assertTrue("${callers.size}", callers.size <= Usage.MAX_CALLERS)
        assertTrue(callers.any { it.address == Usage.OTHERS })
        // Nothing is lost by folding: the totals still account for every tile.
        assertEquals(Usage.MAX_CALLERS * 3, Usage.totalTiles())
    }

    @Test fun theLoopbackAddressIsCalledThisPhone() {
        assertEquals("this phone", Usage.label("127.0.0.1"))
        assertEquals("this phone", Usage.label("::1"))
        assertEquals("192.168.1.5", Usage.label("192.168.1.5"))
    }

    @Test fun anAddressWeWereNotToldIsStillARow() {
        Usage.served("", 10, 1)
        assertEquals("unknown", Usage.callers().single().address)
    }

    @Test fun bytesAreWrittenInAUnitSomebodyCanJudge() {
        assertEquals("900 B", Usage.formatBytes(900))
        assertEquals("40 kB", Usage.formatBytes(40_000))
        assertEquals("2.5 MB", Usage.formatBytes(2_500_000))
        assertEquals("1.2 GB", Usage.formatBytes(1_200_000_000))
    }

    // --- the keys this server issues ------------------------------------------------------------

    private fun bytes(seed: Int) = ByteArray(16) { (seed + it).toByte() }

    @Test fun aKeyHasTheShapeEverythingElseLooksFor() {
        val key = Access.make("laptop", bytes(1), 1_000)
        assertTrue(key.value, Access.looksLikeKey(key.value))
        assertTrue(key.value.startsWith(Access.PREFIX))
        assertEquals(Access.PREFIX.length + Access.BODY_LENGTH, key.value.length)
        assertEquals("laptop", key.label)
    }

    @Test fun twoKeysFromDifferentBytesAreDifferent() {
        assertFalse(Access.make("a", bytes(1), 1).value == Access.make("b", bytes(9), 1).value)
    }

    @Test fun aKeyIsMadeOfHexAndNothingElse() {
        val key = Access.make("x", ByteArray(16) { (-it).toByte() }, 1)
        assertTrue(key.value, key.value.drop(4).all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test fun tooFewBytesIsRefusedRatherThanQuietlyShortened() {
        try {
            Access.make("x", ByteArray(4), 1)
            org.junit.Assert.fail("a short key should not be made at all")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("bytes"))
        }
    }

    @Test fun anUnnamedKeyStillHasALabel() {
        assertEquals("unnamed", Access.make("   ", bytes(2), 1).label)
        assertEquals("unnamed", Access.labelOf("!!!"))
    }

    @Test fun somethingThatIsNotOneOfOursIsNotMistakenForOne() {
        assertFalse(Access.looksLikeKey("mms_short"))
        // Built from halves: a 32-hex literal is the shape the build gate scans the history
        // for, and it cannot tell a fixture from somebody's real key.
        assertFalse(Access.looksLikeKey("0123456789abcdef" + "0123456789abcdef"))
        assertFalse(Access.looksLikeKey("mms_" + "Z".repeat(32)))
        assertFalse(Access.looksLikeKey(""))
    }

    @Test fun thisPhoneNeverNeedsAKey() {
        listOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "127.0.0.53").forEach {
            assertTrue(it, Access.mayServe(it, null, emptyList(), requireFromNetwork = true))
        }
    }

    @Test fun theNetworkNeedsOneAndTheRightOne() {
        val key = Access.make("laptop", bytes(3), 1)
        val keys = listOf(key)
        assertFalse(Access.mayServe("192.168.1.5", null, keys, true))
        assertFalse(Access.mayServe("192.168.1.5", "mms_" + "0".repeat(32), keys, true))
        assertTrue(Access.mayServe("192.168.1.5", key.value, keys, true))
    }

    @Test fun revokingAKeyTakesEffectAtOnce() {
        val key = Access.make("laptop", bytes(4), 1)
        assertTrue(Access.mayServe("192.168.1.5", key.value, listOf(key), true))
        assertFalse(Access.mayServe("192.168.1.5", key.value, emptyList(), true))
    }

    @Test fun theDoorCanBeLeftOpenOnPurpose() {
        assertTrue(Access.mayServe("192.168.1.5", null, emptyList(), requireFromNetwork = false))
    }

    @Test fun theKeyIsReadFromTheQueryOfTheRequest() {
        val route = Http.route("GET /tiles/croatia/12/2229/1460.png?key=mms_abc HTTP/1.1")
        assertEquals("mms_abc", (route as Http.Route.Tile).query["key"])
    }

    @Test fun otherParametersDoNotDisturbTheKey() {
        val route = Http.route("GET /tiles/croatia/12/1/1.png?v=2&key=mms_abc&cachebust=99 HTTP/1.1")
        val query = (route as Http.Route.Tile).query
        assertEquals("mms_abc", query["key"])
        assertEquals("2", query["v"])
    }

    @Test fun aMalformedQueryIsSkippedRatherThanThrown() {
        val query = Access.query("/tiles/x/1/1/1.png?&=&key=mms_abc&broken")
        assertEquals("mms_abc", query["key"])
        assertEquals(1, query.size)
    }

    @Test fun anEscapedValueIsDecoded() {
        assertEquals("a b", Access.decode("a+b"))
        assertEquals("a b", Access.decode("a%20b"))
        assertEquals("a%zzb", Access.decode("a%zzb"))
        assertEquals("plain", Access.decode("plain"))
    }

    @Test fun theUrlForAnotherDeviceCarriesTheKey() {
        val key = Access.make("laptop", bytes(5), 1)
        val url = Access.templateFor("192.168.1.9", 8088, "croatia", key)
        assertTrue(url.startsWith("http://192.168.1.9:8088/tiles/croatia/{z}/{x}/{y}.png?key="))
        assertTrue(url.endsWith(key.value))
        assertFalse(Access.templateFor("192.168.1.9", 8088, "croatia", null).contains("key="))
    }

    @Test fun aKeyInAListIsShownMaskedNotWhole() {
        val key = Access.make("laptop", bytes(6), 1)
        val masked = Access.mask(key)
        assertFalse(masked.contains(key.value.drop(4).take(20)))
        assertTrue(masked.endsWith(key.value.takeLast(4)))
    }

    // --- the status page --------------------------------------------------------------------------

    @Test fun theStatusJsonIsWellFormedAndCarriesTheNumbers() {
        Usage.served("127.0.0.1", 30_000, 5_000)
        val json = Usage.statusJson(uptimeMs = 65_000, maps = listOf("croatia"), port = 8088)
        assertTrue(json.trim().startsWith("{"))
        assertTrue(json.trim().endsWith("}"))
        assertTrue(json.contains("\"uptimeSeconds\": 65"))
        assertTrue(json.contains("\"port\": 8088"))
        assertTrue(json.contains("\"tilesServed\": 1"))
        assertTrue(json.contains("\"maps\": [\"croatia\"]"))
        assertTrue(json.contains("\"address\": \"127.0.0.1\""))
        assertEquals(count(json, "{"), count(json, "}"))
        assertEquals(count(json, "["), count(json, "]"))
    }

    @Test fun aQuoteInANameCannotBreakTheJson() {
        assertEquals("a\\\"b", Usage.escape("a\"b"))
        assertEquals("a\\\\b", Usage.escape("a\\b"))
        assertFalse(Usage.escape("a\u0007b").contains("\u0007"))
    }

    @Test fun anEmptyServerStillProducesValidJson() {
        val json = Usage.statusJson(0, emptyList(), 8088)
        assertEquals(count(json, "{"), count(json, "}"))
        assertTrue(json.contains("\"callers\": ["))
    }

    private fun count(text: String, needle: String): Int {
        var i = 0
        var n = 0
        while (true) {
            val at = text.indexOf(needle, i)
            if (at < 0) return n
            n++
            i = at + needle.length
        }
    }
}
