package com.trafficcapture.mitm

import java.net.Socket
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.X509ExtendedKeyManager

/**
 * 基于内存的动态 KeyManager：按主机名返回对应的证书与私钥。
 */
class DynamicKeyManager : X509ExtendedKeyManager() {
    private val keyMap = mutableMapOf<String, Pair<PrivateKey, Array<X509Certificate>>>()

    fun put(host: String, key: PrivateKey, chain: Array<X509Certificate>) {
        keyMap[host.lowercase()] = key to chain
    }

    private fun resolve(alias: String?): Pair<PrivateKey, Array<X509Certificate>>? {
        if (alias == null) return null
        return keyMap[alias.lowercase()]
    }

    override fun getClientAliases(keyType: String?, issuers: Array<java.security.Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<java.security.Principal>?, socket: Socket?): String? = null
    override fun getServerAliases(keyType: String?, issuers: Array<java.security.Principal>?): Array<String>? = keyMap.keys.toTypedArray()
    override fun chooseServerAlias(keyType: String?, issuers: Array<java.security.Principal>?, socket: Socket?): String? = keyMap.keys.firstOrNull()
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = resolve(alias)?.second
    override fun getPrivateKey(alias: String?): PrivateKey? = resolve(alias)?.first
}
