package org.fluidmessages

import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CompanionPairingTest {
    // Two throwaway self-signed certificates, made for this test only (CN=Test a, CN=Test b).
    private val certA = """
        -----BEGIN CERTIFICATE-----
        MIIBdjCCAR2gAwIBAgIUfmcfd6y3F3r0tbj/MHFwMhvgBoYwCgYIKoZIzj0EAwIw
        ETEPMA0GA1UEAwwGVGVzdCBhMB4XDTI2MTEyNjA0MjMwNFoXDTM2MTEyMzA0MjMw
        NFowETEPMA0GA1UEAwwGVGVzdCBhMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE
        WT41omEdwD7oZ/nAjH9o5wwUx6TPtYKtzDeRohLHKuiv5KXGJmb1NlEzFl70qinx
        0RS+u+h+fvWWP5ZMLZbbFqNTMFEwHQYDVR0OBBYEFCSUu15z890v+nljLUc6lmoq
        SyOpMB8GA1UdIwQYMBaAFCSUu15z890v+nljLUc6lmoqSyOpMA8GA1UdEwEB/wQF
        MAMBAf8wCgYIKoZIzj0EAwIDRwAwRAIgP/mMxdnW3g4Bhp1qeGd0Wh1JeT5IzEkG
        d+NDKVM5TNECID4X1d1Q+jyivywYCq4tC4panMeWkSts547l1NQr5H+0
        -----END CERTIFICATE-----
    """.trimIndent()
    private val certB = """
        -----BEGIN CERTIFICATE-----
        MIIBdzCCAR2gAwIBAgIUNtV8Q2JUslw8qfJrUtNkr02h+3YwCgYIKoZIzj0EAwIw
        ETEPMA0GA1UEAwwGVGVzdCBiMB4XDTI2MTEyNjA0MjMwNFoXDTM2MTEyMzA0MjMw
        NFowETEPMA0GA1UEAwwGVGVzdCBiMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE
        y39XFke0rrc2fdv8QW6YblavgZ/hDkkMs28Jvvr/GOCFjTXKYrCp8yqwOdw+2e2E
        JGJJ5EHn2yWHWVwxtZWoWqNTMFEwHQYDVR0OBBYEFH37DfRS7s3NB6knskP/sksg
        wdUqMB8GA1UdIwQYMBaAFH37DfRS7s3NB6knskP/sksgwdUqMA8GA1UdEwEB/wQF
        MAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIgIhyIGTuRj5uPa//ZUKUodYwVCXCG1X5m
        ycTdHmsCDsoCIQDZsBNg5c7o7vg94kfiTlGSinMArEaB2jPGJASqIXMmlg==
        -----END CERTIFICATE-----
    """.trimIndent()
    // openssl x509 -noout -fingerprint -sha256 for certA.
    private val shownA = "8E:9D:D7:5C:71:83:5E:37:90:BE:D3:DC:49:FA:A1:73:62:1F:F3:09:11:6B:E6:26:45:5A:0A:85:8B:25:71:9B"
    private val pinA = shownA.replace(":", "").lowercase()
    private val token = "0123456789abcdef".repeat(4)

    private fun cert(pem: String) =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate

    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    @Test fun readsThePairingCode() {
        val code = "fluidmessages://pair?a=${enc("https://192.168.1.20:8081")}&a=${enc("https://100.64.1.2:8081")}" +
            "&o=${enc("https://messages.example.net")}&t=$token&f=${enc(shownA)}"
        val payload = CompanionPairing.parse(code)!!
        assertEquals(listOf("https://192.168.1.20:8081", "https://100.64.1.2:8081"), payload.pinned)
        assertEquals(listOf("https://messages.example.net"), payload.others)
        assertEquals(token, payload.token)
        assertEquals(pinA, payload.fingerprint)
        // This computer's own addresses first, with the fingerprint; the proxy's with normal trust.
        assertEquals(listOf(CompanionPairing.Attempt("https://192.168.1.20:8081", pinA), CompanionPairing.Attempt("https://100.64.1.2:8081", pinA),
            CompanionPairing.Attempt("https://messages.example.net", null)), CompanionPairing.attempts(payload))
    }

    @Test fun readsTheWebsCompactCode() {
        // What companion.js pairPayload makes: own addresses without https://, ':' and '/' left as they are, base64url fingerprint.
        val code = "fluidmessages://pair?a=192.168.1.20:8081&a=%5Bfd00::5%5D:8081&o=https://messages.example.net&t=$token" +
            "&f=jp3XXHGDXjeQvtPcSfqhc2If8wkRa-YmRVoKhYslcZs"
        val payload = CompanionPairing.parse(code)!!
        assertEquals(listOf("https://192.168.1.20:8081", "https://[fd00::5]:8081"), payload.pinned)
        assertEquals(listOf("https://messages.example.net"), payload.others)
        assertEquals(pinA, payload.fingerprint)
        assertNull(CompanionPairing.fromBase64("jp3XXHGDXjeQvtPcSfqhc2If8wkRa-YmRVoKhYslcZ"))
        assertNull(CompanionPairing.fromBase64("jp3XXHGDXjeQvtPcSfqhc2If8wkRa+YmRVoKhYslcZs"))
    }

    @Test fun refusesWhatIsntAPairingCode() {
        for (text in listOf("", "https://example.com", "fluidmessages://other?t=$token&o=https%3A%2F%2Fa.example",
            "fluidmessages://pair?a=${enc("https://192.168.1.20:8081")}&f=$pinA",
            "fluidmessages://pair?a=${enc("https://192.168.1.20:8081")}&t=short&f=$pinA",
            "fluidmessages://pair?a=${enc("https://192.168.1.20:8081")}&t=$token&f=1234",
            "fluidmessages://pair?t=$token&f=$pinA", "x".repeat(5000))) {
            assertNull(text.take(60), CompanionPairing.parse(text))
        }
        // http:// addresses are never taken from a code, and own addresses without a fingerprint aren't either.
        assertNull(CompanionPairing.parse("fluidmessages://pair?a=${enc("http://192.168.1.20:8080")}&t=$token&f=$pinA"))
        assertNull(CompanionPairing.parse("fluidmessages://pair?a=${enc("https://192.168.1.20:8081")}&t=$token"))
        // A bad address is skipped; the good one stays.
        val mixed = CompanionPairing.parse("fluidmessages://pair?a=${enc("https://x.example/path")}&a=${enc("https://192.168.1.20:8081")}&t=$token&f=$pinA")!!
        assertEquals(listOf("https://192.168.1.20:8081"), mixed.pinned)
    }

    @Test fun normalizesFingerprints() {
        assertEquals(pinA, CompanionPairing.normalize(shownA))
        assertEquals(pinA, CompanionPairing.normalize(" " + shownA.replace(":", " ").lowercase() + " "))
        assertEquals(pinA, CompanionPairing.normalize("sha256:$pinA"))
        assertNull(CompanionPairing.normalize(pinA.dropLast(1)))
        assertNull(CompanionPairing.normalize(pinA.dropLast(1) + "g"))
        assertEquals(shownA, CompanionPairing.shown(pinA))
    }

    @Test fun pinMatchesExactlyThatCertificate() {
        val a = cert(certA).encoded
        val b = cert(certB).encoded
        assertEquals(pinA, CompanionPairing.fingerprint(a))
        assertTrue(CompanionPairing.pinMatches(a, pinA))
        assertTrue(CompanionPairing.pinMatches(a, shownA))
        assertFalse(CompanionPairing.pinMatches(b, pinA))
        assertFalse(CompanionPairing.pinMatches(null, pinA))
        assertFalse(CompanionPairing.pinMatches(a, ""))
        assertFalse(CompanionPairing.pinMatches(a, null))
    }

    @Test fun trustManagerTrustsOnlyThePin() {
        var changed = 0
        val trust = CompanionTls.PinnedTrust(pinA) { changed++ }
        trust.checkServerTrusted(arrayOf(cert(certA)), "ECDHE_ECDSA")
        assertEquals(0, changed)
        try {
            trust.checkServerTrusted(arrayOf(cert(certB)), "ECDHE_ECDSA")
            fail("Another certificate was trusted")
        } catch (error: CompanionTls.CertificateChanged) {
            assertTrue(CompanionTls.changed(javax.net.ssl.SSLHandshakeException("wrapped").apply { initCause(error) }))
        }
        try {
            trust.checkServerTrusted(arrayOf(), "ECDHE_ECDSA")
            fail("No certificate was trusted")
        } catch (error: CompanionTls.CertificateChanged) {}
        assertEquals(2, changed)
        assertEquals(0, trust.acceptedIssuers.size)
        assertFalse(CompanionTls.changed(java.io.IOException("offline")))
    }

    @Test fun pinnedHostIsOnlyTheSavedOne() {
        assertTrue(CompanionPairing.sameHost("https://192.168.1.20:8081", "192.168.1.20"))
        assertTrue(CompanionPairing.sameHost("https://Nas.Example:8081", "nas.example"))
        assertTrue(CompanionPairing.sameHost("https://[fd00::5]:8081", "fd00::5"))
        assertFalse(CompanionPairing.sameHost("https://192.168.1.20:8081", "192.168.1.21"))
        assertFalse(CompanionPairing.sameHost(null, "192.168.1.20"))
        assertFalse(CompanionPairing.sameHost("https://192.168.1.20:8081", null))
    }
}
