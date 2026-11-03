package com.datalens.audit;
import java.time.Instant;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.datalens.permission.PermissionService;
import com.datalens.common.api.*;
import com.datalens.common.web.RequestIdFilter;
@RestController @RequestMapping("/api/v1/audit-events")
public class AuditController {
 public record Event(long id,Long actorId,String action,String resourceType,String resourceId,String outcome,String requestId,Instant occurredAt){}
 private final JdbcTemplate db;private final PermissionService permissions;
 public AuditController(JdbcTemplate db,PermissionService permissions){this.db=db;this.permissions=permissions;}
 @GetMapping public ApiResponse<PageResult<Event>> list(@Valid @ModelAttribute PageQuery page,HttpServletRequest request){permissions.requireSuperAdmin();var rows=db.query("SELECT * FROM sys_audit_event ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?",(r,n)->new Event(r.getLong("id"),r.getObject("actor_id",Long.class),r.getString("action"),r.getString("resource_type"),r.getString("resource_id"),r.getString("outcome"),r.getString("request_id"),r.getTimestamp("occurred_at").toInstant()),page.size(),((long)page.page()-1)*page.size());long total=db.queryForObject("SELECT COUNT(*) FROM sys_audit_event",Long.class);permissions.requireSuperAdmin();return ApiResponse.success(new PageResult<>(rows,total,page.page(),page.size()),RequestIdFilter.id(request));}
}
