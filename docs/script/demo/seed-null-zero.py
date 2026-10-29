"""在 VM Docker 宿主机执行：创建专用缺测/零值历史演示，不删除其他设备数据。

python3 docs/script/demo/seed-null-zero.py
沿用运行容器配置，不打印密码/token；设备禁用，防止模拟器以后填平缺口。
历史为显式合成测试夹具，直接写入 InfluxDB，不伪造在线状态或最新值。
"""
import datetime
import json
import math
import subprocess
import time
import urllib.request


def container_env(container):
    lines = subprocess.check_output(['docker', 'inspect', '-f',
        '{{range .Config.Env}}{{println .}}{{end}}', container], text=True).splitlines()
    return dict(x.split('=', 1) for x in lines if '=' in x)


env = container_env('conduit-conduit-server-1')
base = 'http://127.0.0.1:8091'
token = None


def api(method, path, body=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(base + path, method=method, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    with urllib.request.urlopen(req, timeout=20) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


token = api('POST', '/api/v1/auth/login', {'username': env['CONDUIT_ADMIN_USERNAME'],
    'password': env['CONDUIT_ADMIN_PASSWORD']})['accessToken']
name = '缺测与零值对照（演示）'
products = []
page = 1
while True:
    rows = api('GET', f'/api/v1/products?page={page}&size=100')['items']
    products.extend(rows)
    if len(rows) < 100:
        break
    page += 1
product = next((p for p in products if p['name'] == name), None)
if product is None:
    product = api('POST', '/api/v1/products', {'name': name,
        'description': '专用合成历史：20分钟正常→15分钟缺测→10分钟真实0→15分钟正常；禁用设备防止补报填平。'})
pk = product['productKey']
properties = api('GET', f'/api/v1/products/{pk}/properties')
if not any(p['identifier'] == 'temp' for p in properties):
    api('POST', f'/api/v1/products/{pk}/properties', {'identifier': 'temp',
        'name': '零值与缺测对照', 'dataType': 'DOUBLE', 'unit': '℃', 'decimals': 1})
devices = api('GET', f'/api/v1/devices?productKey={pk}&page=1&size=100')['items']
device = next((d for d in devices if d['name'] == name), None)
if device is None:
    device = api('POST', '/api/v1/devices', {'productKey': pk, 'name': name})['device']
dk = device['deviceKey']
api('PUT', f'/api/v1/devices/{dk}', {'name': name, 'status': 'DISABLED'})
tenant_id = subprocess.check_output(['docker', 'exec', 'conduit-postgres-1', 'psql',
    '-U', 'conduit', '-d', 'conduit', '-Atc', "select id from tenant where code='default'"], text=True).strip()
end = int(time.time() // 60) * 60000
start = end - 3600000
lines = []
for seconds in range(0, 3600, 2):
    if 1200 <= seconds < 2100:
        continue
    value = 0.0 if seconds < 2700 and seconds >= 2100 else round(22 + 2 * math.sin(seconds / 100), 1)
    lines.append(f'property,tenant_id={tenant_id},product_key={pk},device_key={dk} temp={value:.1f} {start + seconds * 1000}')
request = urllib.request.Request('http://127.0.0.1:8096/api/v3/write_lp?db=telemetry&precision=millisecond',
    data='\n'.join(lines).encode(), headers={'Authorization': 'Bearer ' + env['INFLUX_TOKEN'], 'Content-Type': 'text/plain'})
with urllib.request.urlopen(request, timeout=20) as response:
    assert response.status in (200, 204)
view = api('GET', f'/api/v1/devices/{dk}/history?properties=temp&from={start}&to={end}')
points = view['series'][0]['points']
assert view['bucketMs'] is not None
assert any(p[1] is None for p in points)
assert any(p[1] == 0 for p in points)
assert any(isinstance(p[1], (int, float)) and p[1] > 0 for p in points)
def iso(ms):
    return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).isoformat()
print(json.dumps({'name': name, 'deviceKey': dk, 'written': len(lines),
    'from': iso(start), 'to': iso(end), 'gapFrom': iso(start + 1200000),
    'gapTo': iso(start + 2100000), 'zeroTo': iso(start + 2700000),
    'bucketMs': view['bucketMs'], 'nullBuckets': sum(p[1] is None for p in points),
    'zeroBuckets': sum(p[1] == 0 for p in points),
    'url': f'http://192.168.126.128:8090/devices/{dk}?tab=history&props=temp'}, ensure_ascii=False))
