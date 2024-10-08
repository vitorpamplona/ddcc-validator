package org.who.gdhcnvalidator.verify.shc

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import org.who.gdhcnvalidator.QRDecoder
import org.who.gdhcnvalidator.trust.TrustRegistry
import java.io.ByteArrayOutputStream
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.util.*
import java.util.zip.Inflater

class ShcVerifier (private val registry: TrustRegistry) {
    private val URI_SCHEMA = "shc"
    private val SMALLEST_B64_CHAR_CODE = 45

    data class JWTHeader(
        val alg: String?,
        val kid: String?,
        val zip: String?,
    )

    data class JWT(
        val header: JWTHeader,
        val payload: JWTPayload
    )

    data class JWTRaw(
        val header: ByteArray,
        val payload: ByteArray,
        val verified: ByteArray,
    )

    fun inflateRaw(byteArray: ByteArray): ByteArray {
        val decompresser = Inflater(true)
        decompresser.setInput(byteArray)

        val outputStream = ByteArrayOutputStream(byteArray.size)
        val buffer = ByteArray(1024)
        while (!decompresser.finished()) {
            val count: Int = decompresser.inflate(buffer)
            outputStream.write(buffer, 0, count)
        }
        outputStream.close()
        return outputStream.toByteArray()
    }

    private fun parseJWT(token: String): JWTRaw? {
        return try {
            val parts = token.split(".") // always returns 3 parts

            JWTRaw(
                Base64.getUrlDecoder().decode(parts[0]),
                Base64.getUrlDecoder().decode(parts[1]),
                Base64.getUrlDecoder().decode(parts[2])
            )
        } catch (e: Throwable) {
            return null
        }
    }

    private fun parsePayload(jwt: JWTRaw): JWT? {
        return try {
            val mapper = jacksonObjectMapper()
            val header = mapper.readValue(jwt.header.toString(Charsets.UTF_8), JWTHeader::class.java)
            var payloadRaw = jwt.payload

            if (header.zip.toString().equals("DEF")) {
                payloadRaw = inflateRaw(jwt.payload)
            }

            val payload = mapper.readValue(payloadRaw, JWTPayload::class.java)

            JWT(header, payload)
        } catch (e: Throwable) {
            e.printStackTrace()
            return null
        }
    }

    private fun fromBase10(base10: String): String? {
        return try {
            return base10.chunked(2)
                .map { Char(it.toInt() + SMALLEST_B64_CHAR_CODE) }
                .joinToString("")
        } catch (e: Throwable) {
            null
        }
    }

    fun unpack(uri: String): String? {
        return fromBase10(prefixDecode(uri))
    }

    private fun prefixDecode(uri: String): String {
        var data = uri.lowercase()

        // Backwards compatibility.
        if (data.startsWith(URI_SCHEMA)) {
            data = data.substring(3)
            if (data.startsWith(':')) {
                data = data.substring(1)
            }
            if (data.startsWith('/')) {
                data = data.substring(1)
            }
        }

        return data
    }

    private fun decodeSignedMessage(decodedBytes: String): SignedJWT? {
        return try {
            return SignedJWT.parse(decodedBytes)
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    private fun getKID(jwt: JWT): String? {
        if (jwt.payload.iss == null || jwt.header.kid == null)
            return null

        return "${jwt.payload.iss}#${jwt.header.kid}"
    }

    private fun resolveIssuer(kid: String): TrustRegistry.TrustedEntity? {
        return registry.resolve(TrustRegistry.Framework.SHC, kid)
    }

    private fun verify(signedMessage: SignedJWT, pubKey: PublicKey): Boolean {
        return try {
            val key = ECDSAVerifier(pubKey as ECPublicKey)
            signedMessage.verify(key)
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    /**
     * avoids using the content parsed and converted to objects
     */
    fun originalContents(jwt: JWTRaw): String {
        val mapper = jacksonObjectMapper()

        val headerStr = jwt.header.toString(Charsets.UTF_8);
        val originalHeader = mapper.readTree(headerStr)

        var payloadRaw = jwt.payload
        if (originalHeader["zip"].toString().contains("DEF")) {
            payloadRaw = inflateRaw(jwt.payload)
        }
        val originalPayload = mapper.readTree(payloadRaw)

        var root = mapper.createObjectNode()
        root.put("header", originalHeader)
        root.put("body", originalPayload)

        return mapper.writeValueAsString(root)
    }

    fun unpackAndVerify(uri: String): QRDecoder.VerificationResult {
        val hc1Decoded = prefixDecode(uri)
        val decodedBytes = fromBase10(hc1Decoded) ?: return QRDecoder.VerificationResult(QRDecoder.Status.INVALID_ENCODING, null, null, uri, null)
        val jwtRaw = parseJWT(decodedBytes) ?: return QRDecoder.VerificationResult(QRDecoder.Status.INVALID_ENCODING, null, null, uri, null)
        val jwt = parsePayload(jwtRaw) ?: return QRDecoder.VerificationResult(QRDecoder.Status.INVALID_COMPRESSION, null, null, uri, null)

        val unpacked = originalContents(jwtRaw)

        val signedMessage = decodeSignedMessage(decodedBytes) ?: return QRDecoder.VerificationResult(
            QRDecoder.Status.INVALID_SIGNING_FORMAT, null, null, uri, unpacked)

        val contents = ShcMapper().run(jwt.payload)

        val kid = getKID(jwt) ?: return QRDecoder.VerificationResult(QRDecoder.Status.KID_NOT_INCLUDED, contents, null, uri, unpacked)
        val issuer = resolveIssuer(kid) ?: return QRDecoder.VerificationResult(QRDecoder.Status.ISSUER_NOT_TRUSTED, contents, null, uri, unpacked)

        return when (issuer.status) {
            TrustRegistry.Status.TERMINATED -> QRDecoder.VerificationResult(QRDecoder.Status.TERMINATED_KEYS, contents, issuer, uri, unpacked)
            TrustRegistry.Status.EXPIRED -> QRDecoder.VerificationResult(QRDecoder.Status.EXPIRED_KEYS, contents, issuer, uri, unpacked)
            TrustRegistry.Status.REVOKED -> QRDecoder.VerificationResult(QRDecoder.Status.REVOKED_KEYS, contents, issuer, uri, unpacked)
            TrustRegistry.Status.CURRENT ->
                if (verify(signedMessage, issuer.publicKey))
                    QRDecoder.VerificationResult(QRDecoder.Status.VERIFIED, contents, issuer, uri, unpacked)
                else
                    QRDecoder.VerificationResult(QRDecoder.Status.INVALID_SIGNATURE, contents, issuer, uri, unpacked)
        }
    }
}