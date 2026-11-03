package com.datalens.identity;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class LoginAdmissionTest {
 @Test void saturationDoesNotQueueAndClosedPermitsAreReused() {
    var redis=mock(StringRedisTemplate.class);
    when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList())).thenReturn(1L);
    var admission=new LoginAdmission(redis);
    try(var first=admission.enter("alpha");var second=admission.enter("beta")) {
        assertThatThrownBy(()->admission.enter("gamma")).isInstanceOf(com.datalens.common.web.DomainException.class);
        assertThatThrownBy(()->admission.enter("alpha")).isInstanceOf(com.datalens.common.web.DomainException.class);
    }
    try(var next=admission.enter("alpha")) {assertThat(next).isNotNull();}
 }
 @Test void redisFailureFailsClosedAndReleasesBothPermits() {
    var redis=mock(StringRedisTemplate.class);
    when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList()))
        .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("unavailable")).thenReturn(1L);
    var admission=new LoginAdmission(redis);
    assertThatThrownBy(()->admission.enter("alpha")).isInstanceOf(org.springframework.data.redis.RedisConnectionFailureException.class);
    try(var next=admission.enter("alpha")) {assertThat(next).isNotNull();}
 }
}
