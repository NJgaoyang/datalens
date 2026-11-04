package com.datalens.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared per-window login admission using one atomic MySQL upsert per counter.
 * The transactional read is safe even for concurrent callers: INSERT/UPDATE holds
 * the row lock until the count is read and committed.
 */
@Repository
public class LoginRateLimitStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public LoginRateLimitStore(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc;
        this.transaction=new TransactionTemplate(manager);
    }
    public int increment(String key) {
        Integer count=transaction.execute(status -> {
            jdbc.update("""
                INSERT INTO sys_login_rate_limit (bucket_key,window_start,attempts)
                VALUES (?,UTC_TIMESTAMP(6),1)
                ON DUPLICATE KEY UPDATE
                  attempts=IF(window_start <= UTC_TIMESTAMP(6) - INTERVAL 60 SECOND,1,attempts+1),
                  window_start=IF(window_start <= UTC_TIMESTAMP(6) - INTERVAL 60 SECOND,
                                  UTC_TIMESTAMP(6),window_start)
                """,key);
            return jdbc.queryForObject(
                    "SELECT attempts FROM sys_login_rate_limit WHERE bucket_key=?",Integer.class,key);
        });
        if(count==null) throw new IllegalStateException("Missing login admission count");
        return count;
    }
}
