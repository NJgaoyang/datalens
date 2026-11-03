package com.datalens.export;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import com.datalens.query.QueryModels.*;
class SafeCsvTest {
 @Test void escapesHeadersCellsQuotesAndFormulaPrefixes(){var result=new Result(List.of(new Column("=HEADER","STRING")),List.of(List.of("  =HYPERLINK(\"bad\")"),List.of("+1"),List.of("-1"),List.of("@SUM(A1)"),List.of("\tcmd"),List.of("a,b\n\"c\"")),false,1000);String csv=new String(SafeCsv.encode(result),StandardCharsets.UTF_8);assertThat(csv).startsWith("\uFEFF\"'=HEADER\"\r\n").contains("\"'  =HYPERLINK(\"\"bad\"\")\"").contains("\"'+1\"").contains("\"'-1\"").contains("\"'@SUM(A1)\"").contains("\"'\tcmd\"").contains("\"a,b\n\"\"c\"\"\"");}
 @Test void refusesTruncatedRowsAndOversize(){assertThatThrownBy(()->SafeCsv.encode(new Result(List.of(),List.of(),true,1000))).hasMessageContaining("截断");assertThatThrownBy(()->SafeCsv.encode(new Result(List.of(new Column("x","STRING")),List.of(List.of("x".repeat(4_000_001))),false,1000))).hasMessageContaining("限制");}
 @Test void preservesDecimalPrecisionAndNull(){var values=new ArrayList<String>();values.add("9007199254740993.1200");values.add(null);assertThat(new String(SafeCsv.encode(new Result(List.of(new Column("v","DECIMAL"),new Column("n","STRING")),List.of(values),false,1000)),StandardCharsets.UTF_8)).contains("\"9007199254740993.1200\",\"\"");}
}
