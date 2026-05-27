package jp.co.tisa.signage_android.data

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-CBC 暗号化/復号ユーティリティ。
 * サーバーと共通鍵を使い、SMBパスワード等の機密データを復号する。
 *
 * 暗号化仕様:
 * - アルゴリズム: AES-256-CBC
 * - 鍵の元文字列: ENCRYPTION_KEY_SOURCE
 * - 鍵の導出: SHA-256(元文字列) → 32バイトの鍵
 * - IV: 暗号文の先頭16バイトに付与
 * - パディング: PKCS7 (= PKCS5Padding in Java)
 * - 出力形式: Base64エンコード文字列
 */
object CryptoUtils {

    // サーバーと共通の暗号化鍵の元文字列
    // SHA-256でハッシュしてから256bit鍵として使用
    private const val ENCRYPTION_KEY_SOURCE = "s1gn4g3_2024_AES_k3y_!@#\$5678xxxx"

    // SHA-256で導出した鍵（遅延初期化）
    private val keyBytes: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(ENCRYPTION_KEY_SOURCE.toByteArray(Charsets.UTF_8))
    }

    /**
     * AES-256-CBCで暗号化されたBase64文字列を復号する。
     * 復号に失敗した場合は入力値をそのまま返す（平文フォールバック）。
     */
    fun decryptAes256Cbc(encryptedBase64: String): String {
        return try {
            val data = Base64.decode(encryptedBase64, Base64.DEFAULT)
            if (data.size < 17) {
                // IV(16) + 最低1バイトの暗号文が必要
                return encryptedBase64
            }
            val iv = data.copyOfRange(0, 16)
            val cipherText = data.copyOfRange(16, data.size)
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            // 復号失敗時は平文として扱う（開発/テスト時の互換性）
            e.printStackTrace()
            encryptedBase64
        }
    }
}
