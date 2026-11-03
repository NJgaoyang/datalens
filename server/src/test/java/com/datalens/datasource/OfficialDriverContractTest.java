package com.datalens.datasource;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
/** Exercises packaged official drivers/property recognition only. Never attempts a network connection. */
class OfficialDriverContractTest {
    @Test void analysisDriverIsSeparateAndRecognizesSecurityProperties() throws Exception {
        Class.forName("com.starrocks.cj.jdbc.Driver");Class.forName("com.mysql.cj.jdbc.Driver");
        String url="jdbc:starrocks://10.20.30.40:9030/default_catalog.sales";
        Driver analysis=DriverManager.getDriver(url),metadata=DriverManager.getDriver("jdbc:mysql://127.0.0.1:3306/datalens");
        assertThat(analysis.getClass()).isNotEqualTo(metadata.getClass());
        var properties=new Properties();
        properties.setProperty("sslMode","VERIFY_IDENTITY");properties.setProperty("connectTimeout","5000");
        properties.setProperty("socketTimeout","10000");properties.setProperty("allowLoadLocalInfile","false");
        properties.setProperty("allowMultiQueries","false");properties.setProperty("allowPublicKeyRetrieval","false");
        var descriptors=Arrays.asList(analysis.getPropertyInfo(url,properties));
        for(String name:properties.stringPropertyNames()) {
            var descriptor=descriptors.stream().filter(d->d.name.equals(name)).findFirst().orElseThrow();
            assertThat(descriptor.value).as(name).isEqualTo(properties.getProperty(name));
        }
    }
}
