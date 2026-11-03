package com.datalens.identity;
import java.time.Instant;
/** Internal database row. Never returned from a controller or written to logs. */
public record UserAccount(Long id, String username, String passwordHash, String role,
        boolean enabled, long authVersion, int failedAttempts, Instant lockedUntil) {
    @Override public String toString() { return "UserAccount[id=" + id + "]"; }
    public UserView view() { return new UserView(id, username, role, enabled); }
}
