package com.yausername.youtubedl_android

import java.io.File

/** Deterministic config selection only. Does not change OpenSSL policy. */
internal fun MutableMap<String, String>.selectOwnedPythonConfig(pythonHome: String) {
    this["OPENSSL_CONF"] = File(pythonHome, "etc/tls/openssl.cnf").absolutePath
}
