package com.datalens.datasource;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.datalens.common.web.DomainException;
import static com.datalens.datasource.SourceModels.*;

/** Exact numeric IPv4 endpoints only: no DNS rebinding, JDBC URL injection or wildcard hosts. */
@Component
public class EndpointPolicy {
    private final Set<String> endpoints;
    public EndpointPolicy(@Value("${DATALENS_SOURCE_ALLOWED_ENDPOINTS:}") String allowed) {
        endpoints=new HashSet<>();
        for(String entry:allowed.split(",")) if(!entry.isBlank()) endpoints.add(entry.trim());
    }
    public void validate(String host,int port) {
        if(host==null || !host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}") ||
            Arrays.stream(host.split("\\.")).anyMatch(p->Integer.parseInt(p)>255) ||
            port<1 || port>65535 || !endpoints.contains(host+":"+port))
            throw new DomainException(400,"SOURCE_ENDPOINT_DENIED","数据源地址不在允许范围内");
    }
    public String jdbcUrl(SourceView source) {
        validate(source.host(),source.port());
        for(String identifier:List.of(source.catalog(),source.database()))
            if(!identifier.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
                throw new DomainException(400,"INVALID_SOURCE_SCOPE","数据源范围无效");
        return "jdbc:starrocks://"+source.host()+":"+source.port()+"/"+source.catalog()+"."+source.database();
    }
}
