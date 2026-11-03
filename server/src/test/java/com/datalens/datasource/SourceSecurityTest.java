package com.datalens.datasource;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import com.datalens.common.web.DomainException;
import static com.datalens.datasource.SourceModels.*;

class SourceSecurityTest {
    private String key() {return Base64.getEncoder().encodeToString(new byte[32]);}
    @Test void secretsUseUniqueNonceAndAuthenticatedSourceBinding() {
        var secrets=new SourceSecrets(key());String a=secrets.encrypt("source-a","secret"),b=secrets.encrypt("source-a","secret");
        assertThat(a).doesNotContain("secret").isNotEqualTo(b);
        assertThat(secrets.decrypt("source-a",a)).isEqualTo("secret");
        assertThatThrownBy(()->secrets.decrypt("source-b",a)).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->secrets.decrypt("source-a",a.substring(0,a.length()-4)+"AAAA")).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->new SourceSecrets(Base64.getEncoder().encodeToString(new byte[16])).encrypt("id","secret")).isInstanceOf(DomainException.class);
    }
    @Test void missingKeyAllowsConstructionButNotCredentialOperations() {
        var secrets=new SourceSecrets("");
        assertThatThrownBy(()->secrets.encrypt("id","secret")).isInstanceOf(DomainException.class)
            .extracting(e->((DomainException)e).code()).isEqualTo("SOURCE_KEY_UNAVAILABLE");
    }
    @Test void endpointsAreExactNumericIpAndPortAndDefaultDeny() {
        var policy=new EndpointPolicy("10.20.30.40:9030");policy.validate("10.20.30.40",9030);
        for(String host:List.of("localhost","010.20.30.40","10.20.30.40?x=1","10.20.30.41","10.20.30.40/","[::1]","256.20.30.40"))
            assertThatThrownBy(()->policy.validate(host,9030)).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->policy.validate("10.20.30.40",3306)).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->new EndpointPolicy("").validate("10.20.30.40",9030)).isInstanceOf(DomainException.class);
    }
    @Test void credentialsAreRedactedInStringRepresentations() {
        var in=new SourceInput("name","10.20.30.40",9030,"default_catalog","sales","reader","password-secret",true,List.of("orders"));
        assertThat(in.toString()).doesNotContain("password-secret");
        assertThat(new StoredSource(null,"ciphertext-secret").toString()).doesNotContain("ciphertext-secret");
    }
    @Test void metadataPatternsEscapeWildcards() {assertThat(MetadataCollector.escapePattern("sales_orders","\\")).isEqualTo("sales\\_orders");}
}
