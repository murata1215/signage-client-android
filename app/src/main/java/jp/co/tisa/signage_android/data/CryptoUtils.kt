package jp.co.tisa.signage_android.data

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-CBC 暗号化/復号ユーティリティ。
 * サーバーと共通鍵を使い、SMBパスワード等の機密データを復号する。
 *
 * 暗号化仕様:
 * - アルゴリズム: AES-256-CBC
 * - 鍵: 32文字（256bit）の共通鍵
 * - IV: 暗号文の先頭16バイトに付与
 * - 出力形式: Base64エンコード文字列
 */
object CryptoUtils {

    // サーバーと共通の暗号化鍵（32文字 = 256bit）
    // 本番運用前に変更すること
    private const val ENCRYPTION_KEY = "s1gn4g3_2024_AES_k3y_!@#\$5678xx"

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
            val iv = data.sliceArray(0..15)
            val cipherText = data.sliceArray(16 until data.size)
            val keySpec = SecretKeySpec(ENCRYPTION_KEY.toByteArray(Charsets.UTF_8), "AES")
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
