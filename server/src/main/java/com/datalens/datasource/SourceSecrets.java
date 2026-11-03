package com.datalens.datasource;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.datalens.common.web.DomainException;

/** No fallback key, no persistent plaintext, unique nonce and source-bound authentication. */
@Component
public class SourceSecrets {
    private final String configuredKey;
    private final SecureRandom random=new SecureRandom();
    public SourceSecrets(@Value("${DATALENS_SOURCE_KEY:}") String key) { configuredKey=key; }
    private SecretKeySpec key() {
        try {
            byte[] bytes=Base64.getDecoder().decode(configuredKey);
            if(bytes.length!=32) throw new IllegalArgumentException();
            return new SecretKeySpec(bytes,"AES");
        } catch(IllegalArgumentException e) { throw new DomainException(503,"SOURCE_KEY_UNAVAILABLE","数据源密钥未配置或无效"); }
    }
    public String encrypt(String sourceId,String plaintext) {
        var key=key();
        try {
            byte[] nonce=new byte[12];random.nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            cipher.updateAAD(sourceId.getBytes(StandardCharsets.UTF_8));
            return "v1."+Base64.getEncoder().encodeToString(nonce)+"."+Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception e) { throw new DomainException(503,"SOURCE_KEY_UNAVAILABLE","无法保护数据源凭据"); }
    }
    public String decrypt(String sourceId,String encrypted) {
        var key=key();
        try {
            String[] parts=encrypted.split("\\.");
            if(parts.length!=3 || !parts[0].equals("v1")) throw new IllegalArgumentException();
            byte[] nonce=Base64.getDecoder().decode(parts[1]);
            if(nonce.length!=12) throw new IllegalArgumentException();
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            cipher.updateAAD(sourceId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])),StandardCharsets.UTF_8);
        } catch(Exception e) { throw new DomainException(503,"SOURCE_CREDENTIAL_UNAVAILABLE","数据源凭据不可用"); }
    }
}
