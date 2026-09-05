package jp.co.tisa.signage_android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxySpecTest {

    @Test
    fun `scheme with host and port`() {
        val spec = ProxySpec.parse("http://210.175.128.100:8080")
        assertEquals("210.175.128.100:8080", spec?.toRule())
    }

    @Test
    fun `no scheme with host and port`() {
        val spec = ProxySpec.parse("210.175.128.100:8080")
        assertEquals("210.175.128.100:8080", spec?.toRule())
    }

    @Test
    fun `scheme with host only defaults to port 80`() {
        val spec = ProxySpec.parse("http://proxy.example.com")
        assertEquals("proxy.example.com:80", spec?.toRule())
    }

    @Test
    fun `whitespace and trailing slash are trimmed`() {
        val spec = ProxySpec.parse("  http://p:8080/  ")
        assertEquals("p:8080", spec?.toRule())
    }

    @Test
    fun `empty string is null`() {
        assertNull(ProxySpec.parse(""))
    }

    @Test
    fun `null input is null`() {
        assertNull(ProxySpec.parse(null))
    }

    @Test
    fun `host only without scheme or port is null`() {
        assertNull(ProxySpec.parse("proxy"))
    }

    @Test
    fun `out of range port is null`() {
        assertNull(ProxySpec.parse("http://p:99999"))
    }

    @Test
    fun `blank string is null`() {
        assertNull(ProxySpec.parse("   "))
    }

    @Test
    fun `https scheme is accepted`() {
        val spec = ProxySpec.parse("https://p:443")
        assertEquals("p:443", spec?.toRule())
    }

    @Test
    fun `non numeric port is null`() {
        assertNull(ProxySpec.parse("http://p:abc"))
    }

    @Test
    fun `trailing colon without port is null`() {
        assertNull(ProxySpec.parse("http://p:"))
    }

    @Test
    fun `zero port is out of range`() {
        assertNull(ProxySpec.parse("http://p:0"))
    }

    @Test
    fun `port at upper bound is accepted`() {
        val spec = ProxySpec.parse("http://p:65535")
        assertEquals("p:65535", spec?.toRule())
    }

    @Test
    fun `toJavaProxy builds HTTP proxy`() {
        val spec = ProxySpec(host = "210.175.128.100", port = 8080)
        val proxy = spec.toJavaProxy()
        assertEquals(java.net.Proxy.Type.HTTP, proxy.type())
    }
}
