// Presentation helpers deliberately contain no session, browser storage or network state.
export function titleFromQuestion(question: string): string {
  return Array.from(question.trim().replace(/\s+/gu, ' ')).slice(0, 60).join('') || '新分析';
}
export function shouldSubmitQuestion(event: Pick<KeyboardEvent, 'key'|'shiftKey'|'ctrlKey'|'altKey'|'metaKey'|'isComposing'|'keyCode'>): boolean {
  return event.key === 'Enter' && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey && !event.isComposing && event.keyCode !== 229;
}
