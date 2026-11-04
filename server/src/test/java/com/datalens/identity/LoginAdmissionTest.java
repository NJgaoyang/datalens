package com.datalens.identity;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LoginAdmissionTest {
 @Test void saturationDoesNotQueueAndClosedPermitsAreReused() {
    var store=mock(LoginRateLimitStore.class);
    when(store.increment(anyString())).thenReturn(1);
    var admission=new LoginAdmission(store);
    try(var first=admission.enter("alpha");var second=admission.enter("beta")) {
        assertThatThrownBy(()->admission.enter("gamma")).isInstanceOf(com.datalens.common.web.DomainException.class);
        assertThatThrownBy(()->admission.enter("alpha")).isInstanceOf(com.datalens.common.web.DomainException.class);
    }
    try(var next=admission.enter("alpha")) {assertThat(next).isNotNull();}
 }
 @Test void mysqlFailureFailsClosedAndReleasesPermits() {
    var store=mock(LoginRateLimitStore.class);
    when(store.increment(anyString()))
        .thenThrow(new DataAccessResourceFailureException("unavailable")).thenReturn(1);
    var admission=new LoginAdmission(store);
    assertThatThrownBy(()->admission.enter("alpha"))
        .isInstanceOf(com.datalens.common.web.DomainException.class)
        .extracting(e->((com.datalens.common.web.DomainException)e).code())
        .isEqualTo("DEPENDENCY_UNAVAILABLE");
    try(var next=admission.enter("alpha")) {assertThat(next).isNotNull();}
 }
 @Test void perUsernameEleventhAttemptIsDenied() {
    var store=mock(LoginRateLimitStore.class);
    when(store.increment("datalens:login:global")).thenReturn(1);
    when(store.increment(startsWith("datalens:login:name:"))).thenReturn(10,11);
    var admission=new LoginAdmission(store);
    try(var first=admission.enter("alpha")) {assertThat(first).isNotNull();}
    assertThatThrownBy(()->admission.enter("alpha"))
        .isInstanceOf(com.datalens.common.web.DomainException.class)
        .extracting(e->((com.datalens.common.web.DomainException)e).code()).isEqualTo("RATE_LIMITED");
 }
}
