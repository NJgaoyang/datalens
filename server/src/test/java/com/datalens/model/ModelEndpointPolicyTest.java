package com.datalens.model;

import com.datalens.common.web.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ModelEndpointPolicyTest {
    @Test void exactPublicHttpsEndpointOnly() {
        var policy = new ModelEndpointPolicy("https://8.8.8.8/v1/chat/completions");
        assertDoesNotThrow(() -> policy.validate("https://8.8.8.8/v1/chat/completions"));
        assertThrows(DomainException.class, () -> policy.validate("https://8.8.8.8/v2/chat/completions"));
        assertThrows(DomainException.class, () -> new ModelEndpointPolicy("").validate("https://8.8.8.8/v1/chat/completions"));
    }
    @ParameterizedTest @ValueSource(strings = {
            "http://8.8.8.8/v1/chat/completions", "https://api.openai.com/v1/chat/completions",
            "https://user:secret@8.8.8.8/v1/chat/completions", "https://8.8.8.8/v1/chat/completions?x=1",
            "https://8.8.8.8/v1/chat/completions#fragment", "https://8.8.8.8:0/v1/chat/completions",
            "https://8.8.8.8:99999/v1/chat/completions", "https://8.8.8.8/%2e%2e/admin",
            "https://8.8.8.8/../admin", "https://[::1]/v1/chat/completions", "https://[2606:4700::1111]/chat",
            "https://localhost/chat", "https://127.0.0.1/chat", "https://10.1.2.3/chat",
            "https://172.16.0.1/chat", "https://172.31.255.255/chat", "https://192.168.1.1/chat",
            "https://169.254.169.254/chat", "https://100.64.0.1/chat", "https://100.127.255.255/chat",
            "https://0.0.0.0/chat", "https://192.0.2.1/chat", "https://192.88.99.1/chat",
            "https://198.18.0.1/chat", "https://198.19.255.255/chat", "https://198.51.100.1/chat",
            "https://203.0.113.1/chat", "https://224.0.0.1/chat", "https://255.255.255.255/chat",
            "https://2130706433/chat", "https://0177.0.0.1/chat", "https://256.1.1.1/chat" })
    void rejectsEvenWhenOperatorAllowlisted(String endpoint) {
        assertEquals("MODEL_ENDPOINT_DENIED", assertThrows(DomainException.class,
                () -> new ModelEndpointPolicy(endpoint).validate(endpoint)).code());
    }
}
