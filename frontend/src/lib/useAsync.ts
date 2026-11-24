import { ref } from 'vue'

export function useAsync() {
  const loading = ref(false), error = ref('')
  let generation = 0
  async function run<T>(request: () => Promise<T>, apply?: (value: T) => void): Promise<T | undefined> {
    const ownGeneration = ++generation
    loading.value = true
    error.value = ''
    try {
      const result = await request()
      if (ownGeneration !== generation) return
      apply?.(result)
      return result
    } catch (cause) {
      if (ownGeneration === generation) error.value = cause instanceof Error ? cause.message : '操作暂时不可用'
    } finally {
      if (ownGeneration === generation) loading.value = false
    }
  }
  return { loading, error, run }
}
