import { http } from './client'

export interface Invitation {
  id: string
  email: string
  username: string
  role: 'TENANT_ADMIN' | 'TENANT_VIEWER'
  created_at: string
  expires_at: string
  accepted_at: string | null
  revoked_at: string | null
}

export const invitationsApi = {
  list: () => http.get<Invitation[]>('/api/v1/invitations').then((r) => r.data),
  create: (request: { email: string; username: string; role: Invitation['role'] }) =>
    http.post('/api/v1/invitations', request).then(() => undefined),
  revoke: (id: string) => http.delete(`/api/v1/invitations/${id}`).then(() => undefined),
  preview: (token: string) => http.get<{ username: string; email: string; tenant_name: string }>(
    '/api/v1/invitations/accept', { params: { token } }).then((r) => r.data),
  accept: (token: string, password: string) =>
    http.post('/api/v1/invitations/accept', { token, password }).then(() => undefined),
}
