package com.datalens.semantic;

import java.util.*;
import com.datalens.datasource.SourceModels.*;
import static com.datalens.semantic.SemanticModels.*;

/** Synthetic schema/definitions only: these fixtures are not approved business metrics. */
final class SemanticFixtures {
    static List<ColumnView> columns(String source) { return List.of(
        new ColumnView(source,"orders","id","BIGINT",1,false),
        new ColumnView(source,"orders","amount","DECIMAL",2,true),
        new ColumnView(source,"orders","created_at","DATETIME",3,false),
        new ColumnView(source,"items","order_id","BIGINT",1,false)); }
    static Document document() {
        var fields=List.of(
            new BusinessField("id","订单号",List.of("单号"),new Field("orders","id"),"BIGINT","个","INTERNAL"),
            new BusinessField("amount","实付金额",List.of("金额"),new Field("orders","amount"),"DECIMAL","CNY","INTERNAL"),
            new BusinessField("time","创建时间",List.of(),new Field("orders","created_at"),"DATETIME","时间","INTERNAL"),
            new BusinessField("item_order","明细订单号",List.of(),new Field("items","order_id"),"BIGINT","个","INTERNAL"));
        var metric=metric("sales","销售额","SELECT SUM(amount) AS sales FROM orders WHERE created_at IS NOT NULL");
        return new Document(fields,List.of(metric),List.of(new Dimension("order_dimension","订单",List.of(),"id")),
            List.of(new Relation("order_items","订单明细","id","item_order","ONE_TO_MANY","订单唯一 id","订单明细行")),
            List.of(new Example("sales_example","销售额是多少",List.of("sales"),metric.sql(),List.of(),"Synthetic test only: compare full ledger paid sum, null ignored, refunds excluded.")));
    }
    static Metric metric(String id,String name,String sql) {
        return new Metric(id,name,List.of("营收"),"实付金额，不扣退款","所有状态；测试夹具", "time","Synthetic test owner","CNY","每订单 id 一行","NULL ignored","UTC; [start,end)",List.of("amount","time"),sql,List.of());
    }
    static Document withMetric(Document d,Metric m) {return new Document(d.fields(),List.of(m),d.dimensions(),d.relations(),d.examples());}
}
