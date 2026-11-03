package com.datalens.identity;

import com.datalens.common.web.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** Bound expensive authentication before opening a DB transaction. Redis failure fails closed. */
@Service
public class LoginAdmission {
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>(
        "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n", Long.class);
    private final StringRedisTemplate redis;
    private final Semaphore capacity=new Semaphore(2);
    private final Semaphore[] names=new Semaphore[256];
    public LoginAdmission(StringRedisTemplate redis) {
        this.redis=redis;
        for(int i=0;i<names.length;i++) names[i]=new Semaphore(1);
    }
    public Permit enter(String name) {
        String canonical=IdentityService.username(name);
        Semaphore stripe=names[(canonical.hashCode() & 0x7fffffff)%names.length];
        if(!capacity.tryAcquire()) throw throttled();
        if(!stripe.tryAcquire()) {capacity.release();throw throttled();}
        Permit permit=new Permit(capacity,stripe);
        try {
            Long global=redis.execute(COUNT,List.of("datalens:login:global"));
            if(global==null || global>120) throw throttled();
            Long account=redis.execute(COUNT,List.of("datalens:login:name:"+digest(canonical)));
            if(account==null || account>10) throw throttled();
            return permit;
        } catch(RuntimeException ex) {permit.close();throw ex;}
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
