# M01 identity API

All routes use the existing `{code,message,data,requestId,timestamp}` envelope.
Cookies are same-origin only. Obtain a session-bound CSRF token with `GET /api/v1/auth/csrf` (`data.token`, `data.headerName`) and send that header on **every** mutation, including login. Refresh the token after login/logout. Never persist credentials or tokens in local storage.

| Method | Route | Request / response data |
| --- | --- | --- |
| GET | /api/v1/auth/csrf | `{token,headerName}` |
| POST | /api/v1/auth/login | `{username,password}` -> User |
| GET | /api/v1/auth/me | User |
| POST | /api/v1/auth/logout | empty -> null |
| POST | /api/v1/auth/password | `{currentPassword,newPassword}` -> null, all sessions invalid |
| GET | /api/v1/admin/users | page >= 1, 1 <= size <= 200; `{items,total,page,size}` |
| POST | /api/v1/admin/users | `{username,password,role}` -> User |
| PUT | /api/v1/admin/users/{id}/enabled | `{enabled:boolean}` -> null |
| POST | /api/v1/admin/users/{id}/password | `{newPassword}` -> null, all target sessions invalid |

User is `{id,username,role,enabled}`. Roles: `SUPER_ADMIN`, `USER` only. Admin routes require a current active super admin. Username is canonical lowercase ASCII, 3–64 characters `[a-zA-Z0-9_.-]`; passwords have at least 12 characters and at most 72 UTF-8 bytes. No registration, default account or role-update API exists. The server derives the acting identity; a client userId is never an authority.

401 means authentication required/expired; 403 means forbidden/CSRF failure; 400 means invalid input/current password mismatch; 409 means username conflict/self-disable/last-admin protection; dependency failure is 503. Error responses never include request bodies or internal database details.

Permission extension API: `PermissionService.current()` returns a freshly validated `CurrentActor(id,username,role,authVersion)`. `requireSuperAdmin()` checks fresh state. Async consumers must retain the server-issued actor and call `requireActive(actor)` again at result delivery. Never construct an actor from request fields. Resource-specific authorization is added in its owning later module.

Audit API: `AuditService.append(actorId, action, resourceType, resourceId, outcome, requestId)`. Pass controlled event names and numeric/opaque resource IDs, not arbitrary payloads, SQL, passwords, session tokens or credentials. Current M01 events contain no free-form sensitive payload.
