package com.datalens.identity;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.DefaultCookieSerializer;
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds=1800,redisNamespace="datalens:session")
public class SessionConfiguration {
    @Bean DefaultCookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        var cookie=new DefaultCookieSerializer();
        cookie.setCookieName("DATALENS_SESSION");cookie.setCookiePath("/");
        cookie.setUseHttpOnlyCookie(true);cookie.setUseSecureCookie(secure);cookie.setSameSite("Lax");
        return cookie;
    }
}
