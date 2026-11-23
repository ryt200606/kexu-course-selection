import { ref } from 'vue'
export function useAsync() {
  const loading = ref(false), error = ref('')
  async function run<T>(fn: () => Promise<T>): Promise<T | undefined> { loading.value = true; error.value = ''; try { return await fn() } catch (e) { error.value = e instanceof Error ? e.message : '操作暂时不可用'; return undefined } finally { loading.value = false } }
  return { loading, error, run }
}
