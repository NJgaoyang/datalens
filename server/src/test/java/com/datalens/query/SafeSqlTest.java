package com.datalens.query;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;
import com.datalens.datasource.SourceModels.ColumnView;
import com.datalens.datasource.SourceModels.Field;
import com.datalens.common.web.DomainException;

class SafeSqlTest {
    private static final List<ColumnView> ALLOWED=List.of(
        new ColumnView("source","orders","id","BIGINT",1,false),
        new ColumnView("source","orders","amount","DECIMAL",2,true),
        new ColumnView("source","customers","id","BIGINT",1,false));
    static Stream<String> rejected() {return Stream.of(
        "SELECT * FROM orders", "SELECT o.* FROM orders o", "SELECT secret FROM orders",
        "SELECT id FROM payroll", "SELECT id FROM other.orders", "SELECT id FROM catalog.db.orders",
        "SELECT id FROM information_schema.tables", "SELECT id INTO OUTFILE '/tmp/data' FROM orders",
        "SELECT id INTO tmp FROM orders", "SELECT id FROM orders FOR UPDATE", "SELECT id FROM orders LOCK IN SHARE MODE",
        "SELECT id FROM orders; DELETE FROM orders", "DELETE FROM orders", "UPDATE orders SET amount=0",
        "CREATE TABLE leaked AS SELECT id FROM orders", "DROP TABLE orders", "SET @x=1", "CALL proc()",
        "SHOW TABLES", "EXPLAIN SELECT id FROM orders", "SELECT SLEEP(30) FROM orders",
        "SELECT LOAD_FILE(?) FROM orders", "SELECT DATABASE() FROM orders", "SELECT custom_udf(id) FROM orders",
        "SELECT db.SUM(amount) FROM orders", "SELECT SUM(amount) OVER () FROM orders", "SELECT @@version FROM orders",
        "SELECT @variable FROM orders", "SELECT id FROM FILES('path'='s3://bucket')",
        "SELECT id FROM orders UNION SELECT id FROM payroll", "SELECT id FROM orders UNION SELECT id FROM orders",
        "SELECT id FROM orders o JOIN payroll p ON o.id=p.id", "SELECT o.id FROM orders o JOIN customers c ON o.secret=c.id",
        "SELECT o.id FROM orders o NATURAL JOIN customers c", "SELECT o.id FROM orders o JOIN customers c USING(id)",
        "SELECT o.id FROM orders o CROSS JOIN customers c", "SELECT id FROM orders WHERE secret=1",
        "SELECT id FROM orders GROUP BY secret", "SELECT id FROM orders HAVING secret>0", "SELECT id FROM orders ORDER BY secret",
        "SELECT id FROM orders WHERE id IN (SELECT id FROM payroll)", "SELECT id FROM orders WHERE id IN (SELECT secret FROM orders)",
        "SELECT x.id FROM (SELECT secret AS id FROM orders) x", "WITH x AS (SELECT secret AS id FROM orders) SELECT id FROM x",
        "WITH x AS (DELETE FROM orders RETURNING id) SELECT id FROM x", "WITH RECURSIVE x AS (SELECT id FROM orders) SELECT id FROM x",
        "SELECT id FROM orders WHERE id IN (SELECT c.id FROM customers c WHERE c.id=orders.id)",
        "SELECT id FROM orders WHERE amount='1'", "SELECT id FROM orders LIMIT 100001", "SELECT id FROM orders LIMIT 0",
        "SELECT id FROM orders LIMIT 5 OFFSET 1", "SELECT id FROM orders LIMIT ?", "SELECT id FROM orders WHERE id=:id",
        "SELECT /*+ SET_VAR(query_timeout=99999) */ id FROM orders", "SELECT /*!50000 secret */ id FROM orders",
        "SELECT id FROM orders -- comment", "SELECT id FROM orders # comment", "SELECT id FROM orders WHERE id=?1",
        "SELECT id FROM orders WHERE id=$1", "SELECT id FROM orders WHERE id=1; SELECT id FROM orders",
        "SELECT secret AS id FROM orders ORDER BY id", "SELECT id FROM orders o JOIN customers c ON o.id=c.id",
        "SELECT amount COLLATE utf8_general_ci FROM orders", "SELECT id FROM orders TABLESAMPLE(10 PERCENT)",
        "SELECT DISTINCT ON (secret) id FROM orders", "SELECT TOP 1 id FROM orders", "SELECT SQL_CALC_FOUND_ROWS id FROM orders",
        "SELECT id FROM orders QUALIFY secret>1", "SELECT id FROM orders GROUP BY id WITH ROLLUP",
        "SELECT GROUP_CONCAT(id ORDER BY secret) FROM orders", "SELECT SUM(amount ORDER BY secret) FROM orders",
        "SELECT id FROM orders PROCEDURE ANALYSE()", "SELECT id FROM orders WHERE id=1 OR secret=2",
        "SELECT id FROM orders WHERE id=(SELECT secret FROM orders)", "SELECT id FROM orders WHERE EXISTS (SELECT secret FROM orders)",
        "SELECT id FROM orders WHERE id=1 \\ OR secret=1", "SELECT id FROM orders WHERE id=1\0",
        "SELECT id FROM orders FOR SHARE", "SELECT id FROM orders INTO OUTFILE '/tmp/x'", "SELECT id FROM orders; KILL QUERY 1");}
    @ParameterizedTest @MethodSource("rejected") void rejectsUnsafeAndUnimplementedSql(String sql) {
        assertThrows(DomainException.class,()->SafeSql.validate(sql,ALLOWED,sql.contains("?")?List.of(1):List.of()),sql);
    }
    @Test void emitsOnlyCanonicalSqlAndPreservesBoundValues() {
        var result=SafeSql.validate("SELECT o.id, SUM(o.amount) AS total FROM orders o WHERE o.amount>? GROUP BY o.id ORDER BY total DESC LIMIT 20",ALLOWED,List.of(4));
        assertEquals("SELECT `o`.`id`, SUM(`o`.`amount`) AS `total` FROM `orders` AS `o` WHERE (`o`.`amount` > ?) GROUP BY `o`.`id` ORDER BY `total` DESC LIMIT 20",result.sql());
        assertEquals(Set.of(new Field("orders","id"),new Field("orders","amount")),result.fields());assertEquals(1,result.parameterCount());
        assertEquals(result,SafeSql.validate(result.sql(),ALLOWED,List.of(4)));
    }
    @Test void resolvesAllNestedScopes() {
        var r=SafeSql.validate("WITH x AS (SELECT id FROM customers) SELECT o.id FROM orders o JOIN x ON x.id=o.id WHERE o.id IN (SELECT y.id FROM (SELECT id FROM customers) y)",ALLOWED,List.of());
        assertEquals(Set.of(new Field("customers","id"),new Field("orders","id")),r.fields());
        assertEquals(r,SafeSql.validate(r.sql(),ALLOWED,List.of()));
    }
    @Test void preservesPlaceholderTextualOrderAcrossProjectionJoinAndCte() {
        var r=SafeSql.validate("WITH x AS (SELECT id FROM customers WHERE id>?) SELECT ? AS marker, o.id FROM orders o JOIN x ON o.id=x.id AND o.amount>? WHERE o.id<?",ALLOWED,List.of(1,2,3,4));
        assertEquals("WITH `x` AS (SELECT `customers`.`id` FROM `customers` WHERE (`customers`.`id` > ?)) SELECT ? AS `marker`, `o`.`id` FROM `orders` AS `o` INNER JOIN `x` ON ((`o`.`id` = `x`.`id`) AND (`o`.`amount` > ?)) WHERE (`o`.`id` < ?)",r.sql());
        assertEquals(4,r.parameterCount());
        assertEquals(r,SafeSql.validate(r.sql(),ALLOWED,List.of(1,2,3,4)));
    }
    @Test void bindsHostileValuesWithoutSqlInterpolation() {
        String sql="SELECT id FROM orders WHERE amount=?";
        var r=SafeSql.validate(sql,ALLOWED,List.of("x'; DROP TABLE orders; --"));
        assertFalse(r.sql().contains("DROP"));assertEquals(1,r.parameterCount());
        assertEquals(1,SafeSql.validate(sql,ALLOWED,Collections.singletonList(null)).parameterCount());
    }
    @Test void validatesParameterCount() {
        assertThrows(DomainException.class,()->SafeSql.validate("SELECT id FROM orders WHERE id=?",ALLOWED,List.of()));
        assertThrows(DomainException.class,()->SafeSql.validate("SELECT id FROM orders",ALLOWED,List.of(1)));
    }
    @Test void boundParametersCannotBecomeStructuralIdentifiers() {
        assertThrows(DomainException.class,()->SafeSql.validate("SELECT id FROM ?",ALLOWED,List.of("orders")));
        assertThrows(DomainException.class,()->SafeSql.validate("SELECT id FROM orders ORDER BY id ?",ALLOWED,List.of("DESC")));
    }
    @Test void limitsParsingComplexity() {
        assertThrows(DomainException.class,()->SafeSql.validate("SELECT id FROM orders WHERE "+"(".repeat(25)+"id=1"+")".repeat(25),ALLOWED,List.of()));
        assertThrows(DomainException.class,()->SafeSql.validate(" ".repeat(16001),ALLOWED,List.of()));
    }
    @Test void independentConcurrentCompilationsNeverShareAuthority() throws Exception {
        try(var executor=Executors.newFixedThreadPool(4)) {
            List<Callable<Boolean>> jobs=new ArrayList<>();
            for(int i=0;i<60;i++){final boolean grant=i%2==0;jobs.add(()->{try{SafeSql.validate("SELECT amount FROM orders",grant?ALLOWED:List.of(ALLOWED.getFirst()),List.of());return grant;}catch(DomainException e){return !grant;}});}
            for(var result:executor.invokeAll(jobs))assertTrue(result.get());
        }
    }
}
