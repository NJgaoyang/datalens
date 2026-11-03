package com.datalens.identity;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
/** Narrow read-only identity boundary; never exposes credentials to another module. */
@Service
public class AccountDirectory {
    public record Recipient(long id,String username) {}
    private final IdentityMapper users;
    public AccountDirectory(IdentityMapper users){this.users=users;}
    public CurrentActor active(long id){var u=users.find(id);if(u==null||!u.enabled())throw new AccessDeniedException("Account unavailable");return CurrentActor.from(u);}
    public List<Recipient> recipients(){return users.recipients();}
}
