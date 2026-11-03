package com.datalens.identity;
import com.datalens.audit.AuditService;
import com.datalens.common.api.PageResult;
import com.datalens.common.web.DomainException;
import com.datalens.permission.PermissionService;
import java.time.Instant;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class IdentityService {
    private final IdentityMapper users;
    private final PasswordEncoder passwords;
    private final PermissionService permissions;
    private final AuditService audit;
    private final String dummyHash;
    public IdentityService(IdentityMapper users,PasswordEncoder passwords,PermissionService permissions,AuditService audit) {
        this.users=users;this.passwords=passwords;this.permissions=permissions;this.audit=audit;
        this.dummyHash=passwords.encode(java.util.UUID.randomUUID().toString());
    }
    public static String username(String value) {
        if(value==null || !value.matches("[a-zA-Z0-9_.-]{3,64}"))
            throw new DomainException(400,"VALIDATION_ERROR","用户名必须为 3–64 位字母、数字、点、下划线或横线");
        return value.toLowerCase(Locale.ROOT);
    }
    /** Failure counters and audit must commit even when authentication fails. */
    @Transactional(noRollbackFor=DomainException.class)
    public UserAccount authenticate(String name,String password,String requestId) {
        if(password==null || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new DomainException(401,"UNAUTHENTICATED","用户名或密码错误，或账号暂不可用");
        UserAccount user=users.lockByUsername(username(name));
        Instant now=Instant.now();
        // Admission bounds expensive work; retain equal-cost checks for locked/absent usernames.
        boolean matches=passwords.matches(password, user==null ? dummyHash : user.passwordHash());
        if(user==null || !user.enabled() || (user.lockedUntil()!=null && now.isBefore(user.lockedUntil())) || !matches) {
            if(user!=null && user.enabled() && (user.lockedUntil()==null || !now.isBefore(user.lockedUntil()))) {
                int attempts=user.lockedUntil()!=null ? 1 : user.failedAttempts()+1;
                users.loginFailed(user.id(),attempts,attempts>=5 ? now.plusSeconds(900) : null);
            }
            audit.append(user==null ? null : user.id(),"LOGIN","USER",null,"DENIED",requestId);
            throw new DomainException(401,"UNAUTHENTICATED","用户名或密码错误，或账号暂不可用");
        }
        users.loginSucceeded(user.id());
        audit.append(user.id(),"LOGIN","USER",Long.toString(user.id()),"SUCCESS",requestId);
        return user;
    }
    @Transactional
    public UserView create(String name,String password,String role,String requestId) {
        users.lockAdministration();
        CurrentActor actor=permissions.requireSuperAdmin();
        validateLocked(users.lock(actor.id()),actor);
        return createInternal(name,password,role,actor.id(),requestId);
    }
    private UserView createInternal(String name,String password,String role,Long actor,String requestId) {
        String canonical=username(name);PasswordPolicy.validate(password);
        if(!"USER".equals(role) && !"SUPER_ADMIN".equals(role)) throw new DomainException(400,"VALIDATION_ERROR","角色不合法");
        users.lockAdministration();
        if(users.lockByUsername(canonical)!=null) throw new DomainException(409,"USERNAME_EXISTS","用户名已存在");
        users.create(canonical,passwords.encode(password),role);
        UserAccount user=users.lockByUsername(canonical);
        audit.append(actor,"CREATE_USER","USER",Long.toString(user.id()),"SUCCESS",requestId);
        return user.view();
    }
    @Transactional
    public void bootstrap(String name,String password) {
        users.lockAdministration();
        // Never reset or recreate accounts on restart, even if provisioning variables remain present.
        if(users.count()==0) createInternal(name,password,"SUPER_ADMIN",null,java.util.UUID.randomUUID().toString());
    }
    public PageResult<UserView> list(int page,int size) {
        permissions.requireSuperAdmin();
        return new PageResult<>(users.page((long)(page-1)*size,size),users.count(),page,size);
    }
    @Transactional
    public void changePassword(String current,String replacement,String requestId) {
        CurrentActor actor=permissions.current();
        UserAccount user=users.lock(actor.id());
        validateLocked(user,actor);
        if(current.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72 || !passwords.matches(current,user.passwordHash())) throw new DomainException(400,"PASSWORD_MISMATCH","当前密码不正确");
        replacePassword(actor,user,replacement,"CHANGE_PASSWORD",requestId);
    }
    @Transactional
    public void resetPassword(long id,String replacement,String requestId) {
        users.lockAdministration();
        CurrentActor actor=permissions.requireSuperAdmin();
        validateLocked(users.lock(actor.id()),actor);
        replacePassword(actor,required(users.lock(id)),replacement,"RESET_PASSWORD",requestId);
    }
    private void replacePassword(CurrentActor actor,UserAccount user,String replacement,String action,String requestId) {
        PasswordPolicy.validate(replacement);
        users.password(user.id(),passwords.encode(replacement));
        audit.append(actor.id(),action,"USER",Long.toString(user.id()),"SUCCESS",requestId);
    }
    @Transactional
    public void setEnabled(long id,boolean enabled,String requestId) {
        users.lockAdministration();
        CurrentActor actor=permissions.requireSuperAdmin();
        validateLocked(users.lock(actor.id()),actor);
        UserAccount user=required(users.lock(id));
        if(!enabled && actor.id()==id) throw new DomainException(409,"SELF_DISABLE","不能禁用当前账号");
        if(!enabled && user.enabled() && "SUPER_ADMIN".equals(user.role()) && users.activeAdmins()<=1)
            throw new DomainException(409,"LAST_ADMIN","必须保留一个有效超管");
        if(user.enabled()!=enabled) users.enabled(id,enabled);
        audit.append(actor.id(),enabled ? "ENABLE_USER" : "DISABLE_USER","USER",Long.toString(id),"SUCCESS",requestId);
    }
    private static UserAccount required(UserAccount user) {
        if(user==null) throw new DomainException(404,"NOT_FOUND","用户不存在");return user;
    }
    private static void validateLocked(UserAccount user,CurrentActor actor) {
        if(user==null || !user.enabled() || user.authVersion()!=actor.authVersion())
            throw new DomainException(401,"UNAUTHENTICATED","会话已失效，请重新登录");
    }
}
