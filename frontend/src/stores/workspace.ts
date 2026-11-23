import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { allPages, type Term } from '../lib/api'

export const useWorkspace = defineStore('workspace', () => {
  const terms = ref<Term[]>([]), termId = ref('')
  const currentTerm = computed(() => terms.value.find(term => term.id === termId.value))
  async function loadTerms() { terms.value = await allPages<Term>('/terms'); if (!terms.value.some(term => term.id === termId.value)) termId.value = (terms.value.find(term => term.state === 'ACTIVE') || terms.value[0])?.id || '' }
  return { terms, termId, currentTerm, loadTerms }
})
