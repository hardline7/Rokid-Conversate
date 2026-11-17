package app.conversate.glass.meeting

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM envelope; AAD prevents moving ciphertext between records or sessions. */
class Vault(private val key: SecretKey) {
    fun seal(text: String, purpose: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // AndroidKeyStore requires the provider to generate encryption IVs.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val nonce = cipher.iv
        check(nonce.size == 12) { "지원하지 않는 GCM nonce 길이입니다" }
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return "v1:" + Base64.getEncoder().encodeToString(nonce + encrypted)
    }

    fun open(envelope: String, purpose: String): String {
        require(envelope.startsWith("v1:")) { "지원하지 않는 암호화 기록입니다" }
        val bytes = Base64.getDecoder().decode(envelope.substring(3))
        require(bytes.size >= 28) { "암호화 기록이 손상되었습니다" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    fun write(file: File, text: String, purpose: String) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temporary = File(file.parentFile, file.name + ".pending")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(seal(text, purpose).toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { if (temporary.exists()) temporary.delete() }
    }

    fun read(file: File, purpose: String): String = open(file.readText(Charsets.US_ASCII), purpose)

    companion object {
        private const val ALIAS = "conversate.meeting.data.v1"
        fun androidKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(ALIAS)) return store.getKey(ALIAS, null) as SecretKey
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256).build())
            return generator.generateKey()
        }
    }
}
