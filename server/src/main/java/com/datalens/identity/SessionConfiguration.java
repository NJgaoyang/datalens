package com.datalens.identity;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.session.web.http.DefaultCookieSerializer;

/** Sessions are persisted through Spring Session JDBC; preserve the cookie contract. */
@Configuration
public class SessionConfiguration {
    @Bean DefaultCookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName("DATALENS_SESSION");cookie.setCookiePath("/");
        cookie.setUseHttpOnlyCookie(true);cookie.setUseSecureCookie(secure);cookie.setSameSite("Lax");
        return cookie;
    }
}
