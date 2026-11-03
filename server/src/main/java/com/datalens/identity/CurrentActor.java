package com.datalens.identity;
import java.io.Serializable;
/** Server-issued session subject. Contains no credential material. */
public record CurrentActor(long id, String username, String role, long authVersion) implements Serializable {
    private static final long serialVersionUID = 1L;
    public static CurrentActor from(UserAccount user) {
        return new CurrentActor(user.id(), user.username(), user.role(), user.authVersion());
    }
}
