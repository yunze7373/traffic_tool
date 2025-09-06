package com.trafficcapture

import android.content.Context
import android.os.Environment
import android.util.Log
import android.widget.Toast
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.*

/**
 * HTTPS 流量解密器
 * [重构] 遵循标准的 BouncyCastle 流程，确保证书生成的原子性和一致性。
 */
class HttpsDecryptor(private val context: Context) {

    private var rootCA: X509Certificate? = null
    private var rootPrivateKey: PrivateKey? = null
    private val certificateCache = mutableMapOf<String, X509Certificate>()

    companion object {
        private const val TAG = "HttpsDecryptor"
        private const val CA_KEYSTORE_FILE = "ca_keystore.p12"
        private const val CA_ALIAS = "traffictool_ca"
        private const val KEYSTORE_TYPE = "PKCS12"
        private const val KEYSTORE_PASSWORD = "traffictool"
        private const val SIGNATURE_ALGORITHM = "SHA256withRSA"  // Fixed: Use correct BouncyCastle algorithm name
        private const val KEY_ALGORITHM = "RSA"
        private val BC_PROVIDER = BouncyCastleProvider.PROVIDER_NAME

        // 静态初始化块，确保 BouncyCastle Provider 只被加载一次。
        init {
            if (Security.getProvider(BC_PROVIDER) == null) {
                Security.addProvider(BouncyCastleProvider())
                Log.d(TAG, "BouncyCastleProvider added.")
            }
        }
    }

    init {
        setupRootCA()
    }

    private fun setupRootCA() {
        try {
            if (!loadExistingCA()) {
                Log.d(TAG, "Keystore not found or invalid. Generating a new Root CA.")
                generateRootCA()
                if (rootCA != null && rootPrivateKey != null) {
                    saveCAToKeystore()
                } else {
                    Log.e(TAG, "Failed to generate new Root CA. Certificate or Key is null.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "An error occurred during Root CA setup.", e)
        }
    }

    private fun loadExistingCA(): Boolean {
        return try {
            val keystoreFile = File(context.filesDir, CA_KEYSTORE_FILE)
            if (!keystoreFile.exists()) return false

            val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
            FileInputStream(keystoreFile).use { fis ->
                keyStore.load(fis, KEYSTORE_PASSWORD.toCharArray())
            }

            val certificate = keyStore.getCertificate(CA_ALIAS) as? X509Certificate
            val privateKey = keyStore.getKey(CA_ALIAS, KEYSTORE_PASSWORD.toCharArray()) as? PrivateKey

            if (certificate != null && privateKey != null) {
                rootCA = certificate
                rootPrivateKey = privateKey
                Log.d(TAG, "Successfully loaded Root CA from keystore.")
                true
            } else {
                Log.w(TAG, "Keystore is present but failed to load CA entry.")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Root CA from keystore.", e)
            // Attempt to delete corrupted keystore
            File(context.filesDir, CA_KEYSTORE_FILE).delete()
            false
        }
    }

    /**
     * [重构] 生成根 CA 证书，严格遵循 BouncyCastle 标准流程。
     */
    private fun generateRootCA() {
        try {
            // 1. 生成密钥对，明确指定 Provider
            val keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM, BC_PROVIDER)
            keyPairGenerator.initialize(2048, SecureRandom())
            val keyPair = keyPairGenerator.generateKeyPair()
            rootPrivateKey = keyPair.private

            // 2. 定义证书有效期
            val now = Date()
            val notAfter = Calendar.getInstance().apply {
                time = now
                add(Calendar.YEAR, 10) // 10 年有效期
            }.time

            // 3. 定义证书持有者和颁发者信息 (自签名证书，两者相同)
            val name = X500Name("CN=TrafficTool CA, O=Eizawa, C=US")

            // 4. 构建证书
            val certBuilder = JcaX509v3CertificateBuilder(
                name, // issuer
                BigInteger.valueOf(System.currentTimeMillis()), // serial number
                now, // not before
                notAfter, // not after
                name, // subject
                keyPair.public // public key
            )

            // 5. 添加证书扩展
            // 基本约束：这是一个 CA
            certBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            // 密钥用途：用于证书签名和 CRL 签名
            certBuilder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))

            // 6. 创建签名器，明确指定 Provider
            val contentSigner = JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider(BC_PROVIDER)
                .build(rootPrivateKey)

            // 7. 生成证书，明确指定 Provider
            val certificateHolder = certBuilder.build(contentSigner)
            rootCA = JcaX509CertificateConverter()
                .setProvider(BC_PROVIDER)
                .getCertificate(certificateHolder)

            Log.d(TAG, "Successfully generated new Root CA certificate.")

        } catch (e: Exception) {
            Log.e(TAG, "FATAL: Could not generate Root CA certificate.", e)
            rootCA = null
            rootPrivateKey = null
        }
    }

    private fun saveCAToKeystore() {
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
            keyStore.load(null, null)
            keyStore.setKeyEntry(CA_ALIAS, rootPrivateKey, KEYSTORE_PASSWORD.toCharArray(), arrayOf(rootCA))

            FileOutputStream(File(context.filesDir, CA_KEYSTORE_FILE)).use { fos ->
                keyStore.store(fos, KEYSTORE_PASSWORD.toCharArray())
            }
            Log.d(TAG, "CA certificate and private key saved to keystore.")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving CA to keystore.", e)
        }
    }

    /**
     * 获取 CA 证书的字节数据，用于导出和安装。
     */
    fun getCAcertificateForInstall(): ByteArray? {
        if (rootCA == null) {
            Log.e(TAG, "getCAcertificateForInstall() called, but rootCA is null!")
            return null
        }
        return try {
            rootCA?.encoded
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding CA certificate to bytes.", e)
            null
        }
    }

    // --- 其他方法保持不变 ---

    fun generateCertificateForHost(hostname: String): X509Certificate? {
        if (rootCA == null || rootPrivateKey == null) {
            Log.e(TAG, "Cannot generate host certificate, Root CA is not initialized.")
            return null
        }
        return try {
            certificateCache[hostname]?.let { return it }

            val keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM, BC_PROVIDER)
            keyPairGenerator.initialize(2048)
            val hostKeyPair = keyPairGenerator.generateKeyPair()

            val now = Date()
            val notAfter = Calendar.getInstance().apply { time = now; add(Calendar.YEAR, 1) }.time

            val issuer = X500Name(rootCA!!.issuerX500Principal.name)
            val subject = X500Name("CN=$hostname, O=TrafficTool, C=US")

            val certBuilder = JcaX509v3CertificateBuilder(
                issuer,
                BigInteger.valueOf(System.currentTimeMillis()),
                now,
                notAfter,
                subject,
                hostKeyPair.public
            )

            val contentSigner = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).setProvider(BC_PROVIDER).build(rootPrivateKey)
            val certificateHolder = certBuilder.build(contentSigner)
            val certificate = JcaX509CertificateConverter().setProvider(BC_PROVIDER).getCertificate(certificateHolder)
            
            certificateCache[hostname] = certificate
            Log.d(TAG, "Generated certificate for $hostname")
            certificate
        } catch (e: Exception) {
            Log.e(TAG, "Error generating certificate for $hostname", e)
            null
        }
    }

    fun createSSLSocketFactory(): SSLSocketFactory {
        return try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate?> = arrayOfNulls(0)
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            })
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            sslContext.socketFactory
        } catch (e: Exception) {
            Log.e(TAG, "Error creating SSL socket factory", e)
            SSLSocketFactory.getDefault() as SSLSocketFactory
        }
    }

    /**
     * 导出CA证书到应用内部存储
     * 返回导出的文件路径，如果失败返回null
     */
    fun exportCACertificate(): String? {
        return try {
            // 确保CA证书已初始化
            if (rootCA == null) {
                setupRootCA()
            }
            
            if (rootCA == null) {
                Log.e(TAG, "CA certificate is not available for export")
                return null
            }

            // 使用应用内部存储目录，不需要特殊权限
            val exportDir = File(context.filesDir, "certificates")
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val certFile = File(exportDir, "traffic_tool_ca.crt")
            
            // 将证书编码为PEM格式
            val certBytes = rootCA!!.encoded
            val pemCert = "-----BEGIN CERTIFICATE-----\n" +
                    java.util.Base64.getEncoder().encodeToString(certBytes)
                        .chunked(64).joinToString("\n") +
                    "\n-----END CERTIFICATE-----"

            // 写入文件
            FileOutputStream(certFile).use { fos ->
                fos.write(pemCert.toByteArray())
            }

            Log.d(TAG, "CA certificate exported successfully to: ${certFile.absolutePath}")
            certFile.absolutePath
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export CA certificate", e)
            null
        }
    }

    /**
     * 获取CA证书信息用于显示
     */
    fun getCACertificateInfo(): String? {
        return try {
            if (rootCA == null) {
                setupRootCA()
            }
            
            rootCA?.let { cert ->
                """
                证书信息:
                主题: ${cert.subjectX500Principal.name}
                颁发者: ${cert.issuerX500Principal.name}
                序列号: ${cert.serialNumber}
                有效期: ${cert.notBefore} 至 ${cert.notAfter}
                算法: ${cert.sigAlgName}
                """.trimIndent()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get CA certificate info", e)
            null
        }
    }
}
