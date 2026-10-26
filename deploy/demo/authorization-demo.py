#!/usr/bin/env python3
"""显式 demo 初始化：通过业务 API 造数据，重复执行不覆盖已有配置或凭据。仅用 Python 标准库。"""
import argparse
import json
import os
import secrets
import sys
from urllib.error import HTTPError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen


class Client:
    def __init__(self, url, token=None):
        self.url, self.token = url.rstrip('/'), token

    def call(self, method, path, body=None, expect=None):
        headers = {'Content-Type': 'application/json'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        req = Request(self.url + '/api/v1' + path,
                      data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            with urlopen(req, timeout=30) as response:
                status, data = response.status, response.read()
        except HTTPError as error:
            status, data = error.code, error.read()
        if expect is not None:
            if status != expect:
                raise RuntimeError(f'{method} {path}: expected {expect}, received {status}')
        elif status >= 400:
            detail = json.loads(data).get('detail', '') if data else ''
            raise RuntimeError(f'{method} {path}: HTTP {status} {detail}')
        return json.loads(data) if data else None

    def login(self, username, password):
        session = self.call('POST', '/auth/login', {'username': username, 'password': password})
        self.token = session['accessToken']
        return session

    def items(self, path):
        rows, page = [], 1
        while True:
            result = self.call('GET', path + ('&' if '?' in path else '?') + urlencode({'page': page, 'size': 100}))
            rows.extend(result['items'])
            if len(rows) >= result['total'] or not result['items']:
                return rows
            page += 1


def ensure_org(api, resource, payload):
    rows = api.call('GET', '/' + resource)
    existing = next((r for r in rows if r['code'] == payload['code']), None)
    if existing:
        for parent in ('customerCode', 'projectCode', 'siteCode'):
            if parent in payload and existing.get(parent) != payload[parent]:
                raise RuntimeError(f'{resource}/{payload["code"]}: existing parent differs; preserve manual configuration')
        return existing
    return api.call('POST', '/' + resource, payload)


def initialize(api, demo_password):
    me = api.call('GET', '/auth/me')
    if me.get('tenantCode') != 'default':
        raise RuntimeError('This fixture is restricted to the default demo tenant')
    api.call('GET', '/roles')
    customer = ensure_org(api, 'customers', {'code': 'authz-demo', 'name': '权限演示客户', 'status': 'ACTIVE'})
    projects, sites, assets = {}, {}, {}
    for code, name in [('a', '权限演示 A（可写）'), ('b', '权限演示 B（只读）'), ('c', '权限演示 C（不可见）')]:
        projects[code] = ensure_org(api, 'projects', {'code': 'authz-demo-' + code, 'name': name,
                                                    'customerCode': customer['code'], 'status': 'ACTIVE'})
        sites[code] = ensure_org(api, 'sites', {'code': 'authz-demo-' + code, 'name': name + '站点',
                                              'projectCode': projects[code]['code'], 'timezone': 'Asia/Shanghai', 'status': 'ACTIVE'})
        assets[code] = ensure_org(api, 'assets', {'code': 'authz-demo-' + code, 'name': name + '设备组',
                                                'siteCode': sites[code]['code'], 'parentCode': None, 'type': '配电柜', 'status': 'ACTIVE'})
    product_name = '权限演示电表'
    product = next((p for p in api.items('/products') if p['name'] == product_name), None)
    if not product:
        product = api.call('POST', '/products', {'name': product_name, 'description': '项目 A 可写、B 只读、C 不可见的授权演示', 'codec': 'standard-json'})
    model_path = '/products/' + quote(product['productKey']) + '/properties'
    existing = {p['identifier'] for p in api.call('GET', model_path)}
    for identifier, name, unit, low, high in [('Ua', 'A 相电压', 'V', 210, 240), ('Ia', 'A 相电流', 'A', 1, 30), ('temp', '设备温度', '℃', 15, 45)]:
        if identifier not in existing:
            api.call('POST', model_path, {'identifier': identifier, 'name': name, 'dataType': 'DOUBLE',
                                         'unit': unit, 'minValue': low, 'maxValue': high, 'decimals': 1})
    devices = {}
    rows = api.items('/devices?' + urlencode({'productKey': product['productKey']}))
    for code in ('a', 'b', 'c'):
        name = '权限演示设备 ' + code.upper()
        device = next((d for d in rows if d['name'] == name), None)
        if not device:
            device = api.call('POST', '/devices', {'productKey': product['productKey'], 'name': name,
                                                 'siteCode': sites[code]['code'], 'assetCode': assets[code]['code']})['device']
        assignment = api.call('GET', '/devices/' + quote(device['deviceKey']) + '/assignment')
        if assignment['siteCode'] != sites[code]['code']:
            raise RuntimeError(f'{name}: existing assignment differs; preserve manual configuration')
        devices[code] = device
    username = 'rbac-demo'
    user = next((u for u in api.items('/users?' + urlencode({'keyword': username})) if u['username'] == username), None)
    if user is None:
        initial_password = 'Init1-' + secrets.token_hex(12)
        api.call('POST', '/users', {'username': username, 'displayName': '权限演示：A可写/B只读', 'role': 'TENANT_VIEWER', 'password': initial_password})
        # 首次登录前先收紧作用域；不留下可直接访问全租户的演示账号。
        api.call('PUT', '/users/' + username + '/bindings', [
            {'roleKey': 'PROJECT_ADMIN', 'scopeType': 'PROJECT', 'projectCode': projects['a']['code']},
            {'roleKey': 'VIEWER', 'scopeType': 'PROJECT', 'projectCode': projects['b']['code']},
        ])
        member = Client(api.url)
        member.login(username, initial_password)
        member.call('PUT', '/me/password', {'oldPassword': initial_password, 'newPassword': demo_password})
    elif user['displayName'] != '权限演示：A可写/B只读':
        raise RuntimeError('rbac-demo already exists with different metadata; no credentials or bindings were overwritten')
    # 已存在用户的密码、状态与绑定均保留（包括人工撤权）；不会为了让验收通过而重置权限。
    return {'username': username, 'product': product, 'projects': projects, 'sites': sites, 'devices': devices}


def verify(url, password, fixture):
    member = Client(url)
    member.login(fixture['username'], password)
    snapshot = member.call('GET', '/authorization/me')
    if snapshot['mode'] != 'enforce':
        raise RuntimeError('Acceptance requires enforce mode; shadow is observation only')
    visible = member.items('/devices?' + urlencode({'productKey': fixture['product']['productKey']}))
    expected = {fixture['devices'][code]['deviceKey'] for code in ('a', 'b')}
    if {d['deviceKey'] for d in visible} != expected:
        raise RuntimeError('Device list differs from A/B visibility; preserve bindings and investigate')
    for code in ('a', 'b', 'c'):
        device = fixture['devices'][code]
        path = '/devices/' + quote(device['deviceKey'])
        member.call('GET', path, expect=404 if code == 'c' else 200)
        # 用原值验证写权限，避免改变演示名称/状态。
        member.call('PUT', path, {'name': device['name'], 'status': device['status']}, expect=200 if code == 'a' else 404)
    member.call('GET', '/users', expect=403)
    print('PASS: A writable; B read-only; C hidden; tenant user administration denied')


def initialize_extended(api, fixture, manager_password, inspector_password):
    """补充自定义巡检角色和由项目管理员委派的现有成员，不改动已存在的授权。"""
    role_key = 'CUSTOM_INSPECTOR'
    existing = next((r for r in api.call('GET', '/roles') if r['role_key'] == role_key), None)
    if existing is None:
        api.call('POST', '/roles', {'roleKey': role_key, 'name': '权限演示巡检员', 'scopeMask': 'PROJECT',
                    'status': 'ACTIVE', 'permissions': ['device.read', 'telemetry.latest.read', 'telemetry.history.read']})
    manager = Client(api.url)
    manager.login(fixture['username'], manager_password)
    account = 'rbac-inspector'
    users = api.items('/users')
    existing_user = next((u for u in users if u['username'] == account), None)
    if existing_user is None:
        initial_password = 'Init1-' + secrets.token_hex(12)
        api.call('POST', '/users', {'username': account, 'displayName': '权限演示：自定义巡检员',
                                  'role': 'TENANT_VIEWER', 'password': initial_password})
        # 由项目管理员使用项目专属接口委派，脚本自身也验证跨项目权限边界。
        manager.call('PUT', '/projects/' + fixture['projects']['a']['code'] + '/members/' + account + '/bindings', [
            {'roleKey': role_key, 'scopeType': 'PROJECT', 'projectCode': fixture['projects']['a']['code']}])
        inspector = Client(api.url)
        inspector.login(account, initial_password)
        inspector.call('PUT', '/me/password', {'oldPassword': initial_password, 'newPassword': inspector_password})
    elif existing_user['displayName'] != '权限演示：自定义巡检员':
        raise RuntimeError('rbac-inspector already exists with different metadata; preserve manual account')
    return account


def verify_extended(url, password, fixture, account):
    inspector = Client(url)
    inspector.login(account, password)
    visible = inspector.items('/devices?' + urlencode({'productKey': fixture['product']['productKey']}))
    if {d['deviceKey'] for d in visible} != {fixture['devices']['a']['deviceKey']}:
        raise RuntimeError('Custom inspector visibility differs from project A; investigate role and binding')
    key_a, key_b = fixture['devices']['a']['deviceKey'], fixture['devices']['b']['deviceKey']
    inspector.call('GET', '/devices/' + key_a, expect=200)
    inspector.call('GET', '/devices/' + key_b, expect=404)
    inspector.call('PUT', '/devices/' + key_a, {'name': fixture['devices']['a']['name'], 'status': 'ENABLED'}, expect=404)
    print('PASS: custom inspector reads only A; cannot write A or see B')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--demo', action='store_true', help='Explicitly confirm this is a demo environment')
    parser.add_argument('--url', default='http://127.0.0.1:8091')
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    if not args.demo:
        parser.error('--demo is required')
    admin_password = os.environ.get('CONDUIT_DEMO_ADMIN_PASSWORD')
    if not admin_password:
        parser.error('Set CONDUIT_DEMO_ADMIN_PASSWORD')
    demo_password = os.environ.get('CONDUIT_DEMO_PASSWORD')
    if not demo_password:
        parser.error('Set CONDUIT_DEMO_PASSWORD; credentials are never embedded in the demo script')
    inspector_password = os.environ.get('CONDUIT_DEMO_INSPECTOR_PASSWORD')
    if not inspector_password:
        parser.error('Set CONDUIT_DEMO_INSPECTOR_PASSWORD')
    api = Client(args.url)
    api.login(os.environ.get('CONDUIT_DEMO_ADMIN_USERNAME', 'admin'), admin_password)
    fixture = initialize(api, demo_password)
    inspector = initialize_extended(api, fixture, demo_password, inspector_password)
    print(json.dumps({'username': fixture['username'], 'devices': {k: {'name': v['name'], 'deviceKey': v['deviceKey']} for k, v in fixture['devices'].items()}}, ensure_ascii=False, indent=2))
    if args.verify:
        verify(args.url, demo_password, fixture)
        verify_extended(args.url, inspector_password, fixture, inspector)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
