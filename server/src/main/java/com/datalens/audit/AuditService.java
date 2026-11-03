package com.datalens.audit;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Service;
/** Structured events only: no free-form payload, username, password, SQL or token is accepted. */
@Service
public class AuditService {
    private final AuditMapper mapper;
    public AuditService(AuditMapper mapper) { this.mapper=mapper; }
    public void append(Long actorId, String action, String resourceType, String resourceId,
            String outcome, String requestId) {
        mapper.insert(actorId, action, resourceType, resourceId, outcome, requestId);
    }
    @Mapper
    public interface AuditMapper {
        @Insert("INSERT INTO sys_audit_event(actor_id,action,resource_type,resource_id,outcome,request_id) " +
                "VALUES(#{actor},#{action},#{type},#{resource},#{outcome},#{requestId})")
        void insert(@Param("actor") Long actor,@Param("action") String action,@Param("type") String type,
            @Param("resource") String resource,@Param("outcome") String outcome,@Param("requestId") String requestId);
    }
}
