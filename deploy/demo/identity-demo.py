#!/usr/bin/env python3
"""Idempotent B3 demo setup: pending invitation and two visible sessions.

Only targets the default demo tenant. Requires CONDUIT_DEMO_ADMIN_PASSWORD in the
environment; does not print tokens or passwords or change existing accounts.
"""
import json
import os
from datetime import datetime, timezone
from urllib.error import HTTPError
from urllib.request import Request, urlopen

BASE = os.environ.get('CONDUIT_API_URL', 'http://localhost:8091').rstrip('/') + '/api/v1'
USER = os.environ.get('CONDUIT_DEMO_ADMIN_USERNAME', 'admin')
PASSWORD = os.environ['CONDUIT_DEMO_ADMIN_PASSWORD']


def call(method, path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = Request(BASE + path, data=json.dumps(body).encode() if body is not None else None,
                  headers=headers, method=method)
    try:
        with urlopen(req, timeout=15) as response:
            data = response.read()
            return json.loads(data) if data else None
    except HTTPError as error:
        raise RuntimeError(f'{method} {path}: HTTP {error.code}') from None


first = call('POST', '/auth/login', {'username': USER, 'password': PASSWORD})
if first['user'].get('tenantCode') != 'default':
    raise RuntimeError('Identity demo is restricted to the default demo tenant')
token = first['accessToken']
sessions = call('GET', '/auth/sessions', token=token)
active = [s for s in sessions if s['revokedAt'] is None and
          datetime.fromisoformat(s['expiresAt'].replace('Z', '+00:00')) > datetime.now(timezone.utc)]
if len(active) < 2:
    call('POST', '/auth/login', {'username': USER, 'password': PASSWORD})

username = 'identity-demo-viewer'
users = call('GET', '/users?keyword=' + username, token=token)['items']
invitations = call('GET', '/invitations', token=token)
pending = [i for i in invitations if i['username'] == username and not i['accepted_at']
           and not i['revoked_at']]
if not any(u['username'] == username for u in users) and not pending:
    call('POST', '/invitations', {'email': 'identity-demo-viewer@example.test',
                                'username': username, 'role': 'TENANT_VIEWER'}, token)
if len(active) > 2:
    current = next(s for s in active if s['current'])
    call('DELETE', '/auth/sessions/' + current['id'], token=token)
print('Identity demo ready: 用户 → 邮件邀请; 个人设置 → 登录会话; Mailpit inbox')
