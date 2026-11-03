package com.datalens.model;
import java.util.*;
import java.time.Instant;
import com.datalens.semantic.SemanticModels.*;
import com.datalens.datasource.SourceModels.Field;
final class ModelFixtures {
    static Publication publication(){return publication("PUBLIC",1);}
    static Publication publication(String sensitivity,long version){
        var f=new BusinessField("amount","Amount",List.of(),new Field("orders","amount"),"DECIMAL","USD",sensitivity);
        var m=new Metric("sales","Sales",List.of(),"SUM(amount)","all","amount","owner","USD","order","ignore","UTC",List.of("amount"),"SELECT SUM(amount) AS sales FROM orders",List.of());
        return new Publication("subject",version,new Document(List.of(f),List.of(m),List.of(),List.of(),List.of()),"a".repeat(64),"scope",1,1,Instant.EPOCH,Instant.EPOCH);
    }
    static String valid(){return "{\"intent\":{\"summary\":\"Sales\",\"needsClarification\":false,\"clarification\":\"\",\"metricIds\":[\"sales\"],\"dimensionIds\":[]},\"plan\":{\"metricId\":\"sales\",\"rowLimit\":1000},\"chartSpec\":{\"type\":\"TABLE\",\"dimension\":null,\"measures\":[\"sales\"]}}";}
}
