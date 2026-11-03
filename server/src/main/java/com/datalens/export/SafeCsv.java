package com.datalens.export;
import java.nio.charset.StandardCharsets;
import com.datalens.query.QueryModels.Result;
import com.datalens.common.web.DomainException;
/** CSV is deliberately text-safe, including headers. No inferred numeric coercion. */
public final class SafeCsv {
 private SafeCsv(){}
 static String cell(String value){
  if(value==null)return "\"\"";
  String stripped=value.stripLeading();
  if(!stripped.isEmpty()&&(stripped.charAt(0)=='='||stripped.charAt(0)=='+'||stripped.charAt(0)=='-'||stripped.charAt(0)=='@')||value.startsWith("\t")||value.startsWith("\r")||value.startsWith("\n"))value="'"+value;
  return "\""+value.replace("\"","\"\"")+"\"";
 }
 public static byte[] encode(Result result){
  if(result.truncated())throw new DomainException(422,"EXPORT_TRUNCATED","结果已截断，请缩小范围后刷新，不能导出为完整数据");
  if(result.rows().size()>1000||result.columns().size()>200)throw new DomainException(422,"EXPORT_TOO_LARGE","导出超出限制");
  var out=new StringBuilder("\uFEFF");out.append(String.join(",",result.columns().stream().map(c->cell(c.name())).toList())).append("\r\n");
  for(var row:result.rows()){if(row.size()!=result.columns().size())throw new IllegalArgumentException("Invalid result shape");out.append(String.join(",",row.stream().map(SafeCsv::cell).toList())).append("\r\n");if(out.length()>4_000_000)throw new DomainException(422,"EXPORT_TOO_LARGE","导出超出限制");}
  byte[] bytes=out.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>4_000_000)throw new DomainException(422,"EXPORT_TOO_LARGE","导出超出限制");return bytes;
 }
}
