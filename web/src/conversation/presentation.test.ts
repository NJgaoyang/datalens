import { describe, expect, it } from 'vitest';
import { shouldSubmitQuestion, titleFromQuestion } from './presentation';
const enter = { key: 'Enter', shiftKey: false, ctrlKey: false, altKey: false, metaKey: false, isComposing: false, keyCode: 13 };
describe('question presentation', () => {
  it('derives a compact title from the question without an additional form', () => { expect(titleFromQuestion('  最近一个月\n各城市的订单金额  ')).toBe('最近一个月 各城市的订单金额'); });
  it('does not split Unicode pairs and respects the server title length', () => { const title = titleFromQuestion('😀'.repeat(80)); expect(Array.from(title)).toHaveLength(60); expect(title.length).toBeLessThanOrEqual(120); });
  it('has a safe empty-title fallback', () => { expect(titleFromQuestion(' \n ')).toBe('新分析'); });
  it('sends only plain Enter', () => { expect(shouldSubmitQuestion(enter)).toBe(true); for (const key of ['shiftKey','ctrlKey','altKey','metaKey'] as const) expect(shouldSubmitQuestion({ ...enter, [key]: true })).toBe(false); expect(shouldSubmitQuestion({ ...enter, key: 'a' })).toBe(false); });
  it('never submits while an input method is composing', () => { expect(shouldSubmitQuestion({ ...enter, isComposing: true })).toBe(false); expect(shouldSubmitQuestion({ ...enter, keyCode: 229 })).toBe(false); });
});
