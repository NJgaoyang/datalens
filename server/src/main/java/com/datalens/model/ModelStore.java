package com.datalens.model;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import com.datalens.common.web.DomainException;

@Repository
public class ModelStore {
    public record Stored(String settings,String encryptedKey) {
        @Override public String toString(){return "Stored[REDACTED]";}
    }
    private final JdbcTemplate db;
    public ModelStore(JdbcTemplate db){this.db=db;}
    public void lock(){db.queryForObject("SELECT setting_value FROM sys_platform_setting WHERE setting_key='model.lock' FOR UPDATE",String.class);}
    public Stored read(){var rows=db.query("SELECT settings_json,encrypted_key FROM model_configuration WHERE id=1",(r,n)->new Stored(r.getString(1),r.getString(2)));return rows.isEmpty()?null:rows.getFirst();}
    public void save(String settings,String encryptedKey){db.update("INSERT INTO model_configuration(id,settings_json,encrypted_key) VALUES(1,?,?) ON DUPLICATE KEY UPDATE settings_json=?,encrypted_key=?",settings,encryptedKey,settings,encryptedKey);}
    /** Conservative UTF-8 byte + output-token reservation, never refunded on ambiguous provider outcome. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void reserve(String id,long actor,String subject,long configVersion,long semanticVersion,String digest,long tokens,long budget){
        String date=LocalDate.now(ZoneOffset.UTC).toString();
        // Duplicate-key no-op UPDATE takes an exclusive row lock immediately. INSERT IGNORE
        // can leave competing shared locks and deadlock when both reservations upgrade to UPDATE.
        db.update("INSERT INTO model_daily_budget(budget_date,reserved_tokens) VALUES(?,0) ON DUPLICATE KEY UPDATE reserved_tokens=reserved_tokens",date);
        if(db.update("UPDATE model_daily_budget SET reserved_tokens=reserved_tokens+? WHERE budget_date=? AND reserved_tokens<=?",tokens,date,budget-tokens)!=1)
            throw new DomainException(429,"MODEL_BUDGET_EXCEEDED","模型每日令牌预算不足");
        db.update("INSERT INTO model_call(id,actor_id,subject_id,config_version,semantic_version,semantic_digest,reserved_tokens,status) VALUES(?,?,?,?,?,?,?,'RUNNING')",id,actor,subject,configVersion,semanticVersion,digest,tokens);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void finish(String id,String status,String code){db.update("UPDATE model_call SET status=?,error_code=?,finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",status,code,id);}
}
