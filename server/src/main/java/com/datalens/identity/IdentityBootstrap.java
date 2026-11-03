package com.datalens.identity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
@Component
public class IdentityBootstrap implements ApplicationRunner {
    private final IdentityService identities;
    private final String username;
    private final String password;
    public IdentityBootstrap(IdentityService identities,
            @Value("${DATALENS_BOOTSTRAP_USERNAME:}") String username,
            @Value("${DATALENS_BOOTSTRAP_PASSWORD:}") String password) {
        this.identities=identities;this.username=username;this.password=password;
    }
    public void run(ApplicationArguments args) {
        if(username.isBlank() && password.isBlank()) return;
        if(username.isBlank() || password.isBlank()) throw new IllegalStateException("Both bootstrap variables are required");
        identities.bootstrap(username,password);
    }
}
