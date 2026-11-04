import { ref } from 'vue';
// In-memory privacy invalidation only. No questions or titles are retained here.
export const clearHistoryVersion = ref(0);
export function clearHistoryImmediately() { clearHistoryVersion.value++; }
