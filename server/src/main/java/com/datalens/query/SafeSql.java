package com.datalens.query;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.ASTNodeAccessImpl;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;
import com.datalens.datasource.SourceModels.ColumnView;
import com.datalens.datasource.SourceModels.Field;
import com.datalens.common.web.DomainException;

/** Fail-closed compiler for a deliberately small SQL subset. Never executes input or AST.toString().
 * New AST properties fail closed unless explicitly consumed. Parser version is pinned to 5.3.
 * Catalog/database qualification, wildcard projections, recursive/correlated queries, UDFs,
 * hints, set operations, windows, locks and vendor extensions are deliberately unsupported.
 */
public final class SafeSql {
    public record Validated(String sql, Set<Field> fields, int parameterCount) {}
    private record Compiled(String sql, Set<String> outputs) {}
    private static final Set<String> FUNCTIONS=Set.of("COUNT","SUM","AVG","MIN","MAX","ABS","ROUND","FLOOR","CEIL","COALESCE","NULLIF","LOWER","UPPER","LENGTH");
    private static final Map<String,String> OPERATORS=Map.ofEntries(
        Map.entry("EqualsTo","="),Map.entry("NotEqualsTo","<>"),Map.entry("GreaterThan",">"),
        Map.entry("GreaterThanEquals",">="),Map.entry("MinorThan","<"),Map.entry("MinorThanEquals","<="),
        Map.entry("AndExpression","AND"),Map.entry("OrExpression","OR"),Map.entry("Addition","+"),
        Map.entry("Subtraction","-"),Map.entry("Multiplication","*"),Map.entry("Division","/"),Map.entry("Modulo","%"));
    private final Map<String,Set<String>> tables=new LinkedHashMap<>();
    private final Set<Field> fields=new LinkedHashSet<>();
    private int parameters,nodes;
    private SafeSql(List<ColumnView> allowed) {
        for(var c:allowed) tables.computeIfAbsent(name(c.tableName()),k->new LinkedHashSet<>()).add(name(c.columnName()));
    }
    public static Validated validate(String sql,List<ColumnView> allowed,List<?> values) {
        if(sql==null || sql.isBlank() || sql.length()>16000 || allowed==null || values==null || values.size()>100) throw invalid();
        // Defense against parser CPU/stack abuse and dialect-dependent executable comments.
        // These are rejection-only checks, never a fallback authorization mechanism.
        if(sql.contains("/*") || sql.contains("--") || sql.contains("#") || sql.contains("\\") || sql.indexOf('\0')>=0) throw invalid();
        int depth=0;
        for(char c:sql.toCharArray()) {if(c=='(' && ++depth>24) throw invalid();if(c==')')depth--;}
        // Typed values are validated and bound by QueryParameters in the execution service.
        try {
            var statements=CCJSqlParserUtil.parseStatements(sql,p->p.withTimeOut(1000).withAllowComplexParsing(false));
            if(statements.size()!=1 || !(statements.get(0) instanceof Select select)) throw invalid();
            var compiler=new SafeSql(allowed);
            Compiled result=compiler.select(select,new LinkedHashMap<>(),0);
            if(compiler.parameters!=values.size() || compiler.fields.isEmpty()) throw invalid();
            return new Validated(result.sql(),Set.copyOf(compiler.fields),compiler.parameters);
        } catch(DomainException e) {throw e;} catch(Exception | StackOverflowError e) {throw invalid();}
    }
    private Compiled select(Select select,Map<String,Set<String>> inherited,int depth) {
        if(depth>12 || ++nodes>1000) throw invalid();
        if(select.getClass()==ParenthesedSelect.class) {
            var p=(ParenthesedSelect)select; guard(p,"select","alias");
            if(p.getAlias()!=null) alias(p.getAlias());
            return select(p.getSelect(),inherited,depth+1);
        }
        if(select.getClass()!=PlainSelect.class) throw invalid();
        PlainSelect p=(PlainSelect)select;
        guard(p,"withItemsList","selectItems","fromItem","joins","where","groupBy","having","orderByElements","limit","distinct");
        var ctes=new LinkedHashMap<>(inherited);String prefix="";
        if(p.getWithItemsList()!=null) {
            var parts=new ArrayList<String>();
            for(var w:p.getWithItemsList()) {
                guard(w,"statement","alias"); String n=alias(w.getAlias());
                if(ctes.containsKey(n) || tables.containsKey(n) || !(w.getParenthesedStatement() instanceof ParenthesedSelect sub)) throw invalid();
                Compiled c=select(sub,ctes,depth+1);
                if(c.outputs().isEmpty()) throw invalid();
                ctes.put(n,c.outputs());parts.add(quote(n)+" AS ("+c.sql()+")");
            }
            prefix="WITH "+String.join(", ",parts)+" ";
        }
        Map<String,Set<String>> scope=new LinkedHashMap<>();
        Map<String,String> origins=new HashMap<>();
        if(p.getFromItem()==null) throw invalid();
        String from=from(p.getFromItem(),scope,origins,ctes,depth);
        if(p.getJoins()!=null) for(Join j:p.getJoins()) {
            guard(j,"fromItem","onExpressions","inner","left","right","outer");
            if(j.getOnExpressions().size()!=1 || j.isLeft()&&j.isRight()) throw invalid();
            String right=from(j.getRightItem(),scope,origins,ctes,depth);
            String on=expression(j.getOnExpressions().iterator().next(),scope,origins,ctes,depth+1);
            from+=(j.isLeft()?" LEFT JOIN ":j.isRight()?" RIGHT JOIN ":" INNER JOIN ")+right+" ON "+on;
        }
        if(p.getSelectItems()==null || p.getSelectItems().isEmpty() || p.getSelectItems().size()>100) throw invalid();
        List<String> projections=new ArrayList<>();Set<String> outputs=new LinkedHashSet<>();boolean allNamed=true;
        for(var item:p.getSelectItems()) {
            guard(item,"expression","alias");
            String e=expression(item.getExpression(),scope,origins,ctes,depth+1);
            String output=item.getAlias()!=null?alias(item.getAlias()):item.getExpression() instanceof Column c?name(c.getColumnName()):null;
            if(output==null)allNamed=false;else if(!outputs.add(output))throw invalid();
            if(item.getAlias()!=null)e+=" AS "+quote(output);
            projections.add(e);
        }
        String distinct="";
        if(p.getDistinct()!=null) {guard(p.getDistinct());distinct="DISTINCT ";}
        String result=prefix+"SELECT "+distinct+String.join(", ",projections)+" FROM "+from;
        if(p.getWhere()!=null)result+=" WHERE "+expression(p.getWhere(),scope,origins,ctes,depth+1);
        if(p.getGroupBy()!=null) {
            guard(p.getGroupBy(),"groupByExpressions");
            result+=" GROUP BY "+expressions(p.getGroupBy().getGroupByExpressionList(),scope,origins,ctes,depth+1);
        }
        if(p.getHaving()!=null)result+=" HAVING "+expression(p.getHaving(),scope,origins,ctes,depth+1);
        if(p.getOrderByElements()!=null) {
            List<String> orders=new ArrayList<>();
            for(var o:p.getOrderByElements()) {guard(o,"expression","asc","ascDescPresent");
                String term;
                if(o.getExpression() instanceof Column col && col.getTable()==null && outputs.contains(name(col.getColumnName()))) {guard(col,"columnName","table");term=quote(name(col.getColumnName()));}
                else term=expression(o.getExpression(),scope,origins,ctes,depth+1);
                orders.add(term+(o.isAsc()?" ASC":" DESC"));
            }
            result+=" ORDER BY "+String.join(", ",orders);
        }
        if(p.getLimit()!=null) {guard(p.getLimit(),"rowCount");if(!(p.getLimit().getRowCount() instanceof LongValue n) || n.getValue()<1 || n.getValue()>100000)throw invalid();result+=" LIMIT "+n.getValue();}
        return new Compiled(result,allNamed?Set.copyOf(outputs):Set.of());
    }
    private String from(FromItem item,Map<String,Set<String>> scope,Map<String,String> origins,Map<String,Set<String>> ctes,int depth) {
        if(item!=null && item.getClass()==Table.class) {
            Table t=(Table)item;guard(t,"partItems","partDelimiters","alias");
            if(t.getNameParts().size()!=1)throw invalid();String n=name(t.getName());
            String key=t.getAlias()==null?n:alias(t.getAlias());Set<String> columns=ctes.containsKey(n)?ctes.get(n):tables.get(n);
            if(columns==null || columns.isEmpty() || scope.putIfAbsent(key,columns)!=null)throw invalid();
            if(!ctes.containsKey(n))origins.put(key,n);
            return quote(n)+(t.getAlias()==null?"":" AS "+quote(key));
        }
        if(item!=null && item.getClass()==ParenthesedSelect.class) {
            var s=(ParenthesedSelect)item;if(s.getAlias()==null)throw invalid();String key=alias(s.getAlias());
            Compiled c=select(s,ctes,depth+1);
            if(c.outputs().isEmpty() || scope.putIfAbsent(key,c.outputs())!=null)throw invalid();
            return "("+c.sql()+") AS "+quote(key);
        }
        throw invalid();
    }
    private String expression(Expression e,Map<String,Set<String>> scope,Map<String,String> origins,Map<String,Set<String>> ctes,int depth) {
        if(e==null || depth>24 || ++nodes>1000)throw invalid();
        if(e.getClass()==Column.class) {
            Column c=(Column)e;guard(c,"table","columnName","tableDelimiter");
            if(c.getTableDelimiter()!=null && !".".equals(c.getTableDelimiter()))throw invalid();
            String n=name(c.getColumnName());String qualifier=null;
            if(c.getTable()!=null) {Table t=c.getTable();guard(t,"partItems","partDelimiters");if(t.getNameParts().size()!=1)throw invalid();qualifier=name(t.getName());}
            if(qualifier==null) {
                for(var entry:scope.entrySet())if(entry.getValue().contains(n)){if(qualifier!=null)throw invalid();qualifier=entry.getKey();}
            }
            if(qualifier==null || !scope.containsKey(qualifier) || !scope.get(qualifier).contains(n))throw invalid();
            if(origins.containsKey(qualifier))fields.add(new Field(origins.get(qualifier),n));
            return quote(qualifier)+"."+quote(n);
        }
        if(e.getClass()==JdbcParameter.class) {JdbcParameter p=(JdbcParameter)e;guard(p,"index");if(!"?".equals(p.getParameterCharacter()))throw invalid();parameters++;return "?";}
        if(e.getClass()==LongValue.class) {LongValue n=(LongValue)e;return Long.toString(n.getValue());}
        if(e.getClass()==DoubleValue.class) {double n=((DoubleValue)e).getValue();if(!Double.isFinite(n))throw invalid();return BigDecimal.valueOf(n).toPlainString();}
        if(e.getClass()==NullValue.class)return "NULL";
        if(e instanceof BinaryExpression b && OPERATORS.containsKey(e.getClass().getSimpleName())) {
            guard(b,"leftExpression","rightExpression");
            return "("+expression(b.getLeftExpression(),scope,origins,ctes,depth+1)+" "+OPERATORS.get(e.getClass().getSimpleName())+" "+expression(b.getRightExpression(),scope,origins,ctes,depth+1)+")";
        }
        if(e.getClass()==Function.class) {
            Function f=(Function)e;guard(f,"nameparts","parameters","distinct");String n=f.getName().toUpperCase(Locale.ROOT);
            if(!FUNCTIONS.contains(n) || f.getParameters()==null || f.getParameters().isEmpty() || f.getParameters().size()>8)throw invalid();
            return n+"("+(f.isDistinct()?"DISTINCT ":"")+expressions(f.getParameters(),scope,origins,ctes,depth+1)+")";
        }
        if(e.getClass()==IsNullExpression.class) {var n=(IsNullExpression)e;guard(n,"leftExpression","not");return "("+expression(n.getLeftExpression(),scope,origins,ctes,depth+1)+(n.isNot()?" IS NOT NULL)":" IS NULL)");}
        if(e.getClass()==Between.class) {var b=(Between)e;guard(b,"leftExpression","betweenExpressionStart","betweenExpressionEnd","not");return "("+expression(b.getLeftExpression(),scope,origins,ctes,depth+1)+(b.isNot()?" NOT BETWEEN ":" BETWEEN ")+expression(b.getBetweenExpressionStart(),scope,origins,ctes,depth+1)+" AND "+expression(b.getBetweenExpressionEnd(),scope,origins,ctes,depth+1)+")";}
        if(e.getClass()==InExpression.class) {var n=(InExpression)e;guard(n,"leftExpression","rightExpression","not");return "("+expression(n.getLeftExpression(),scope,origins,ctes,depth+1)+(n.isNot()?" NOT IN ":" IN ")+(n.getRightExpression() instanceof ParenthesedExpressionList<?> list?"("+expressions(list,scope,origins,ctes,depth+1)+")":expression(n.getRightExpression(),scope,origins,ctes,depth+1))+")";}
        if(e.getClass()==ParenthesedExpressionList.class) {var list=(ExpressionList<?>)e;if(list.size()!=1)throw invalid();return expression(list.getFirst(),scope,origins,ctes,depth+1);}
        if(e.getClass()==ParenthesedSelect.class) {Compiled s=select((Select)e,ctes,depth+1);if(s.outputs().size()!=1)throw invalid();return "("+s.sql()+")";}
        throw invalid();
    }
    private String expressions(ExpressionList<?> list,Map<String,Set<String>> scope,Map<String,String> origins,Map<String,Set<String>> ctes,int depth) {
        if(list==null || list.isEmpty() || list.size()>100 || !(list.getClass()==ExpressionList.class || list.getClass()==ParenthesedExpressionList.class))throw invalid();
        List<String> parts=new ArrayList<>();for(Expression e:list)parts.add(expression(e,scope,origins,ctes,depth+1));return String.join(", ",parts);
    }
    private static String alias(Alias a) {if(a==null || a.getAliasColumns()!=null && !a.getAliasColumns().isEmpty())throw invalid();return name(a.getName());}
    private static String name(String n) {
        if(n!=null && n.length()>=2 && n.charAt(0)=='`' && n.charAt(n.length()-1)=='`')n=n.substring(1,n.length()-1);
        if(n==null || !n.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))throw invalid();return n;
    }
    private static String quote(String n) {return "`"+name(n)+"`";}
    /** Reject every unconsumed property differing from a newly constructed node's default.
     * This catches hidden dialect options without relying on permissive visitor defaults.
     */
    private static void guard(Object node,String... consumed) {
        Set<String> allow=Set.of(consumed);
        try {
            Object defaults=node.getClass().getConstructor().newInstance();
            for(Class<?> type=node.getClass();type!=Object.class && type!=ASTNodeAccessImpl.class;type=type.getSuperclass())
                for(var f:type.getDeclaredFields()) {
                    if(Modifier.isStatic(f.getModifiers()) || f.isSynthetic() || allow.contains(f.getName()))continue;
                    f.setAccessible(true);Object value=f.get(node),expected=f.get(defaults);
                    if(value instanceof Collection<?> c && c.isEmpty() && (expected==null || expected instanceof Collection<?> d && d.isEmpty()))continue;
                    if(!Objects.equals(value,expected))throw invalid();
                }
        } catch(DomainException e){throw e;} catch(ReflectiveOperationException | RuntimeException e){throw invalid();}
    }
    private static DomainException invalid() {return new DomainException(422,"SQL_NOT_ALLOWED","SQL 不在已验证的只读语法、字段或参数范围内");}
}
