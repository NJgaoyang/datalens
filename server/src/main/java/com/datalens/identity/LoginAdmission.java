package com.datalens.identity;

import com.datalens.common.web.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Bound BCrypt admission; shared rolling counts live in MySQL, not Redis or process memory. */
@Service
public class LoginAdmission {
    private final LoginRateLimitStore limits;
    private final Semaphore capacity=new Semaphore(2);
    private final Semaphore[] names=new Semaphore[256];
    public LoginAdmission(LoginRateLimitStore limits) {
        this.limits=limits;
        for(int i=0;i<names.length;i++) names[i]=new Semaphore(1);
    }
    public Permit enter(String name) {
        String canonical=IdentityService.username(name);
        Semaphore stripe=names[(canonical.hashCode() & 0x7fffffff)%names.length];
        if(!capacity.tryAcquire()) throw throttled();
        if(!stripe.tryAcquire()) {capacity.release();throw throttled();}
        Permit permit=new Permit(capacity,stripe);
        try {
            if(limits.increment("datalens:login:global")>120) throw throttled();
            if(limits.increment("datalens:login:name:"+digest(canonical))>10) throw throttled();
            return permit;
        } catch(RuntimeException failure) {
            permit.close();
            if(failure instanceof DataAccessException)
                throw new DomainException(503,"DEPENDENCY_UNAVAILABLE","登录限流数据库暂不可用");
            throw failure;
        }
    }
    private static String digest(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex) {throw new IllegalStateException("SHA-256 unavailable");}
    }
    private static DomainException throttled() {return new DomainException(429,"RATE_LIMITED","登录请求过多，请稍后重试");}
    public static final class Permit implements AutoCloseable {
        private final Semaphore capacity,stripe;
        private boolean closed;
        private Permit(Semaphore capacity,Semaphore stripe) {this.capacity=capacity;this.stripe=stripe;}
        public void close() {if(!closed) {closed=true;stripe.release();capacity.release();}}
    }
}
