import { http } from './client'

export interface UserSession {
  id: string
  createdAt: string
  lastUsedAt: string
  expiresAt: string
  revokedAt: string | null
  clientSummary: string | null
  current: boolean
}

export const sessionsApi = {
  list: () => http.get<UserSession[]>('/api/v1/auth/sessions').then((r) => r.data),
  revoke: (id: string) => http.delete(`/api/v1/auth/sessions/${id}`).then(() => undefined),
  revokeAll: () => http.delete('/api/v1/auth/sessions').then(() => undefined),
}
