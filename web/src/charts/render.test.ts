import { describe, expect, it } from 'vitest';
import { chartOptions, plotNumber, validateSpec } from './render';
import type { QueryResult } from '../api/queries';
const result: QueryResult = { columns: [{name:'category',jdbcType:'VARCHAR'},{name:'amount',jdbcType:'DECIMAL'}], rows:[['A','9007199254740993.0100']], truncated:false,rowLimit:1000 };
describe('safe chart rendering', () => {
  it('keeps exact strings for TABLE and KPI without aggregating', () => {
    expect(chartOptions({type:'TABLE',dimension:null,measures:[]},result)).toBeNull();
    expect(chartOptions({type:'KPI',dimension:null,measures:['amount']},result)).toBeNull();
    expect(result.rows[0]?.[1]).toBe('9007199254740993.0100');
    expect(() => validateSpec({type:'KPI',dimension:null,measures:['amount']},{...result,rows:[...result.rows,...result.rows]})).toThrow(/一行/);
  });
  it.each(['BAR','LINE','PIE'] as const)('%s tooltips preserve DECIMAL and BIGINT precision and use text rendering', type => {
    const option = chartOptions({type,dimension:'category',measures:['amount']},result)!;
    expect(option.tooltip.renderMode).toBe('richText');
    expect(option.series[0]?.data[0]?.raw).toBe('9007199254740993.0100');
    expect(option.tooltip.formatter({seriesName:'amount',name:'A',data:option.series[0]?.data[0]})).toContain('9007199254740993.0100');
  });
  it('refuses unknown/missing/duplicate columns and unknown specs', () => {
    expect(() => validateSpec({type:'BAR',dimension:'missing',measures:['amount']},result)).toThrow();
    expect(() => validateSpec({type:'BAR',dimension:'category',measures:['amount','amount']},result)).toThrow();
    expect(() => validateSpec({type:'TABLE',dimension:null,measures:[]},{...result,columns:[result.columns[0]!,result.columns[0]!]})).toThrow();
    expect(() => validateSpec({type:'evil' as 'TABLE',dimension:null,measures:[]},result)).toThrow();
  });
  it('refuses incomplete pies, negative/null values and nonfinite plots', () => {
    const spec = {type:'PIE' as const,dimension:'category',measures:['amount']};
    expect(() => chartOptions(spec,{...result,truncated:true})).toThrow(/截断/);
    for (const raw of ['-1',null,'Infinity','NaN','1e999','0xFF','']) expect(() => chartOptions(spec,{...result,rows:[['A',raw]]})).toThrow();
    expect(() => validateSpec({type:'KPI',dimension:null,measures:['amount']},{...result,truncated:true})).toThrow();
  });
  it('does not sum duplicate categories or truncate string numbers', () => {
    const option = chartOptions({type:'BAR',dimension:'category',measures:['amount']},{...result,rows:[['A','1.2300'],['A','2.3400']],truncated:true})!;
    expect(option.series[0]?.data.map(v => v.raw)).toEqual(['1.2300','2.3400']);
    expect(plotNumber(null)).toBeNull(); expect(plotNumber('-3.4')).toBe(-3.4);
  });
  it('rejects numeric JSON cells rather than displaying previously rounded values', () => {
    expect(() => chartOptions({type:'TABLE',dimension:null,measures:[]},{...result, rows:[['A',42 as unknown as string]]})).toThrow();
  });
});

it('rejects nonzero underflow instead of silently plotting zero', () => {
  for (const raw of ['1e-999', '-0.00001e-999', '0.0000001e-999']) expect(() => plotNumber(raw)).toThrow();
  for (const raw of ['0e-999', '-0.000e999', '0.0']) expect(Math.abs(plotNumber(raw)!)).toBe(0);
});
it('enforces the field limit for TABLE before returning', () => {
  const columns = Array.from({length:21}, (_, i) => ({name:`c${i}`,jdbcType:'VARCHAR'}));
  expect(() => validateSpec({type:'TABLE',dimension:null,measures:columns.map(c => c.name)},{columns,rows:[],truncated:false,rowLimit:1000})).toThrow(/20/);
});
