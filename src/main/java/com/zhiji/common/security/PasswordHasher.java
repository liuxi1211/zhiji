package com.zhiji.common.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

/**
 * 口令哈希：PBKDF2WithHmacSHA256（JDK 内置标准算法），刻意不引 BCrypt 等第三方库。
 * 存储为自描述格式 pbkdf2-sha256$迭代次数$盐$派生密钥，参数升级时旧记录仍可校验。
 */
@Component
public class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 600_000;
    private static final int SALT_LENGTH = 16;
    private static final int KEY_LENGTH = 32;
    private static final String PREFIX = "pbkdf2-sha256";

    private final SecureRandom random = new SecureRandom();

    public String hash(String raw) {
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        byte[] derived = derive(raw, salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derived);
    }

    public boolean verify(String raw, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        byte[] derived = derive(raw, salt, Integer.parseInt(parts[1]));
        return MessageDigest.isEqual(expected, derived);
    }

    private byte[] derive(String raw, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, iterations, KEY_LENGTH * 8);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("口令派生失败", e);
        }
    }
}
