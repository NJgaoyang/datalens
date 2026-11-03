package com.datalens.identity;
import java.nio.charset.StandardCharsets;
import com.datalens.common.web.DomainException;
public final class PasswordPolicy {
    private PasswordPolicy() {}
    public static void validate(String password) {
        if(password==null || password.length()<12 || password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new DomainException(400,"VALIDATION_ERROR","密码至少 12 个字符且不超过 72 UTF-8 字节");
    }
}
