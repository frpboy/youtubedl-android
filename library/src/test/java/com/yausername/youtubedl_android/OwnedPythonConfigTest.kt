package com.yausername.youtubedl_android

import org.junit.Assert.*
import org.junit.Test

class OwnedPythonConfigTest {
    @Test fun rootsResolveIndependentlyAndReplaceInheritedSelection() {
        for (root in listOf("/private/one/packages/python/usr", "/private/other root/python/usr")) {
            val env = mutableMapOf("OPENSSL_CONF" to "/unrelated/old.cnf", "SSL_CERT_FILE" to "$root/etc/tls/cert.pem")
            env.selectOwnedPythonConfig(root)
            assertEquals("$root/etc/tls/openssl.cnf", env["OPENSSL_CONF"])
            assertEquals("$root/etc/tls/cert.pem", env["SSL_CERT_FILE"])
            assertFalse(env.containsKey("OPENSSL_MODULES"))
        }
    }
}
