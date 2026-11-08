package com.veyra.gybeyond;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 敏感凭据的本地存储：Android Keystore 里生成 AES-256 密钥，加密后再落 SharedPreferences。
 *
 * <p>为什么不用 EncryptedSharedPreferences：那要引 androidx.security-crypto 这个依赖，
 * 而它已经停止维护（1.1.0 之后官方建议自己用 Keystore）。这里手写四十行就够，
 * 还省掉了 rebase 上游时改 build.gradle 的麻烦。
 *
 * <p>密钥存在 Keystore 里，不出芯片；就算 SharedPreferences 文件被 root 拷走，
 * 换一台设备也解不开（密钥不可导出）。
 */
final class SecureStore {
    private static final String TAG = "SecureStore";
    private static final String PREFS = "veyra.secure";
    private static final String KEY_ALIAS = "veyra.secure.v1";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private SecureStore() {}

    /** 读一段密文；不存在、解密失败都返回 null，调用方当成"没登录"。 */
    static String read(Context context, String name) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString(name, null);
        if (stored == null || stored.isEmpty()) return null;
        try {
            byte[] blob = Base64.decode(stored, Base64.NO_WRAP);
            if (blob.length <= IV_BYTES) throw new IllegalStateException("密文长度异常");
            byte[] iv = new byte[IV_BYTES];
            byte[] payload = new byte[blob.length - IV_BYTES];
            System.arraycopy(blob, 0, iv, 0, IV_BYTES);
            System.arraycopy(blob, IV_BYTES, payload, 0, payload.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(payload), StandardCharsets.UTF_8);
        } catch (Exception error) {
            // 密钥被系统清掉（换机、清数据、指纹重录）时会走到这里，静默当作未登录
            Log.w(TAG, "读取 " + name + " 失败，按未登录处理", error);
            return null;
        }
    }

    static void write(Context context, String name, String value) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (value == null || value.isEmpty()) {
            prefs.edit().remove(name).apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] iv = cipher.getIV();
            byte[] payload = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[iv.length + payload.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(payload, 0, blob, iv.length, payload.length);
            prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply();
        } catch (Exception error) {
            Log.e(TAG, "写入 " + name + " 失败", error);
        }
    }

    static void remove(Context context, String name) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(name).apply();
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(
                new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        // 不要求解锁才能用：后台拉历史时屏幕通常是灭的
                        .setUserAuthenticationRequired(false)
                        .build());
        return generator.generateKey();
    }
}
