package com.datalens.identity;
import java.util.List;
import org.apache.ibatis.annotations.*;
@Mapper
public interface IdentityMapper {
    @Select("SELECT id,username FROM sys_user WHERE enabled=TRUE ORDER BY username,id LIMIT 1000")
    List<AccountDirectory.Recipient> recipients();
    String COLUMNS = "id, username, password_hash, role, enabled, auth_version, failed_attempts, locked_until";
    @Select("SELECT " + COLUMNS + " FROM sys_user WHERE username=#{username} FOR UPDATE")
    UserAccount lockByUsername(String username);
    @Select("SELECT " + COLUMNS + " FROM sys_user WHERE id=#{id}") UserAccount find(long id);
    @Select("SELECT " + COLUMNS + " FROM sys_user WHERE id=#{id} FOR UPDATE") UserAccount lock(long id);
    @Select("SELECT setting_value FROM sys_platform_setting WHERE setting_key='identity.lock' FOR UPDATE") String lockAdministration();
    @Select("SELECT COUNT(*) FROM sys_user") long count();
    @Select("SELECT COUNT(*) FROM sys_user WHERE role='SUPER_ADMIN' AND enabled=TRUE") long activeAdmins();
    @Select("SELECT id,username,role,enabled FROM sys_user ORDER BY id LIMIT #{size} OFFSET #{offset}")
    List<UserView> page(@Param("offset") long offset, @Param("size") int size);
    @Insert("INSERT INTO sys_user(username,password_hash,role) VALUES(#{username},#{hash},#{role})")
    void create(@Param("username") String username,@Param("hash") String hash,@Param("role") String role);
    @Update("UPDATE sys_user SET failed_attempts=0,locked_until=NULL WHERE id=#{id}") void loginSucceeded(long id);
    @Update("UPDATE sys_user SET failed_attempts=#{attempts}, locked_until=#{until} WHERE id=#{id}")
    void loginFailed(@Param("id") long id,@Param("attempts") int attempts,@Param("until") java.time.Instant until);
    @Update("UPDATE sys_user SET password_hash=#{hash},auth_version=auth_version+1,failed_attempts=0,locked_until=NULL WHERE id=#{id}")
    void password(@Param("id") long id,@Param("hash") String hash);
    @Update("UPDATE sys_user SET enabled=#{enabled},auth_version=auth_version+1 WHERE id=#{id}")
    void enabled(@Param("id") long id,@Param("enabled") boolean enabled);
}
