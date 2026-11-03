import type { ChartSpec } from '../api/charts';
import { chartTypes } from '../api/charts';
import { validateResult, type QueryResult } from '../api/queries';
/** Only this whitelist can become ECharts options. Never execute persisted formatter/options. */
export function validateSpec(spec: ChartSpec, result: QueryResult): void {
  validateResult(result);
  if (!spec || !chartTypes.includes(spec.type) || !Array.isArray(spec.measures) || !spec.measures.every(m => typeof m === 'string') || new Set(spec.measures).size !== spec.measures.length || (spec.dimension !== null && typeof spec.dimension !== 'string')) throw new Error('图表配置无效');
  const names = result.columns.map(c => c.name);
  if (new Set(names).size !== names.length) throw new Error('结果列名称重复，请使用唯一 SQL 别名');
  if (spec.measures.some(m => !names.includes(m)) || spec.dimension !== null && !names.includes(spec.dimension)) throw new Error('图表引用了不存在的结果列');
  if (spec.measures.length > 20) throw new Error('最多选择 20 个字段');
  if (spec.type === 'TABLE') { if (spec.dimension !== null) throw new Error('表格不使用维度映射'); return; }
  if (!spec.measures.length || spec.measures.length > 20) throw new Error('请选择 1 至 20 个指标');
  if (spec.type === 'KPI' && (spec.dimension !== null || spec.measures.length !== 1 || result.rows.length > 1 || result.truncated)) throw new Error('KPI 需要至多一行完整的 SQL 聚合结果，不会在浏览器中汇总');
  if (['BAR', 'LINE', 'PIE'].includes(spec.type) && (!spec.dimension || spec.measures.includes(spec.dimension))) throw new Error('请选择维度列');
  for (const measure of spec.measures) {
    const column = result.columns.find(c => c.name === measure)!;
    if (!/(INT|DECIMAL|NUMERIC|DOUBLE|FLOAT|REAL|NUMBER)/i.test(column.jdbcType)) throw new Error('指标字段必须是数值类型');
    if (result.rows.some(row => { const raw = row[names.indexOf(measure)]; return raw !== null && (typeof raw !== 'string' || !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(raw)); })) throw new Error('数值字段格式无效');
  }
  if (spec.type === 'PIE' && (result.truncated || spec.measures.length !== 1)) throw new Error('饼图需要完整结果和一个指标，不能使用截断数据');
}
export function plotNumber(raw: string|null): number|null {
  if (raw === null) return null;
  if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(raw)) throw new Error('绘图指标必须是有限数值');
  const value = Number(raw);
  if (!Number.isFinite(value) || value === 0 && /[1-9]/.test(raw.split(/[eE]/)[0]!)) throw new Error('绘图指标超出有限数值范围');
  return value;
}
export function chartOptions(spec: ChartSpec, result: QueryResult) {
  validateSpec(spec, result);
  if (['TABLE', 'KPI'].includes(spec.type)) return null;
  const d = result.columns.findIndex(c => c.name === spec.dimension);
  const labels = result.rows.map(row => row[d] ?? 'NULL');
  const series = spec.measures.map(name => {
    const index = result.columns.findIndex(c => c.name === name);
    const data = result.rows.map((row, i) => {
      const value = plotNumber(row[index] ?? null);
      if (spec.type === 'PIE' && (value === null || value < 0)) throw new Error('饼图指标不能为负数或 NULL');
      return { name: labels[i], value, raw: row[index] ?? 'NULL' };
    });
    return { name, type: spec.type.toLowerCase(), data, ...(spec.type === 'PIE' ? { radius: '65%' } : {}) };
  });
  return { animation: false, tooltip: { trigger: 'item', renderMode: 'richText', formatter: (p: { seriesName?: string; name?: string; data?: { raw?: string } }) => `${p.seriesName ?? ''}\n${p.name ?? ''}: ${p.data?.raw ?? 'NULL'}` }, legend: { type: 'scroll' }, ...(spec.type === 'PIE' ? {} : { xAxis: { type: 'category', data: labels }, yAxis: { type: 'value' }, grid: { containLabel: true, left: 24, right: 24, bottom: 40 } }), series };
}
