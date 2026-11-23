import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, ApiError, refreshCsrf, type User } from '../lib/api'

export const useAuth = defineStore('auth', () => {
  const user = ref<User | null>(null), initialized = ref(false)
  let initialization: Promise<void> | undefined
  async function initialize() {
    if (initialized.value) return
    if (!initialization) initialization = (async () => { try { user.value = await api<User>('/auth/me') } catch (error) { if (!(error instanceof ApiError) || error.status !== 401) throw error } finally { initialized.value = true } })()
    await initialization
  }
  async function login(username: string, password: string) { await refreshCsrf(); user.value = await api<User>('/auth/login', { method: 'POST', body: { username, password } }); await refreshCsrf(); initialized.value = true }
  async function logout() { await api('/auth/logout', { method: 'POST' }); user.value = null; await refreshCsrf() }
  function expire() { user.value = null; initialized.value = true }
  return { user, initialized, initialize, login, logout, expire }
})
