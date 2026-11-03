package com.datalens.model;

import com.datalens.common.web.DomainException;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Exact HTTPS public numeric IPv4 endpoints only; never performs DNS resolution. */
@Component
public class ModelEndpointPolicy {
    private final Set<String> allowed;
    public ModelEndpointPolicy(@Value("${DATALENS_MODEL_ALLOWED_ENDPOINTS:}") String endpoints) {
        allowed = Arrays.stream(endpoints.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
    public void validate(String endpoint) {
        try {
            if (endpoint == null || !allowed.contains(endpoint)) throw new IllegalArgumentException();
            URI uri = URI.create(endpoint);
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getHost() == null || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_/-]+")
                    || uri.getRawPath().contains("//") || !publicIpv4(uri.getHost()))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new DomainException(400, "MODEL_ENDPOINT_DENIED", "模型地址不在允许范围内");
        }
    }
    private static boolean publicIpv4(String host) {
        if (!host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return false;
        int[] p = Arrays.stream(host.split("\\.")).mapToInt(Integer::parseInt).toArray();
        if (Arrays.stream(p).anyMatch(n -> n > 255)) return false;
        return !(p[0] == 0 || p[0] == 10 || p[0] == 127 || p[0] >= 224
                || (p[0] == 100 && p[1] >= 64 && p[1] <= 127)
                || (p[0] == 169 && p[1] == 254)
                || (p[0] == 172 && p[1] >= 16 && p[1] <= 31)
                || (p[0] == 192 && (p[1] == 0 || p[1] == 168 || (p[1] == 88 && p[2] == 99)))
                || (p[0] == 198 && (p[1] == 18 || p[1] == 19 || (p[1] == 51 && p[2] == 100)))
                || (p[0] == 203 && p[1] == 0 && p[2] == 113));
    }
}
