"""Conduit IoT 压测负载生成器（多进程版）。

为什么是多进程：上一版用「每设备一个线程」，600 线程时受 GIL 与调度开销限制，
约 3,500 条/秒即出现颠簸，而且**目标速率调得越高实际打出越少**
（实测目标 8,000 打出 2,857、目标 10,000 只打出 1,679）。
于是系统的真实容量天花板测不出来——测到的是负载端的极限。
改为多进程后每个进程独立持有一批设备与各自的 paho 事件循环，绕开 GIL。

两个子命令：
  provision --devices N        经 REST 建压测产品与 N 台设备，明文密钥存入 devices.json
  run --devices N --rate R --seconds S [--workers W]
                               以 N 条 MQTT 连接按每设备每秒 R 条发布，统计吞吐与 PUBACK 时延

口径提醒：客户端只能证明「平台已持久接收」（PUBACK），**不能证明已入库**。
过载时 EMQX 会先 PUBACK 再丢弃订阅端队列中的消息，不带消息 id 的设备无从得知。
真实的入库能力必须以服务端的 conduit_pipeline_processed_total 为准。

凭据只从同目录的 config.json 读取（已在 .gitignore 中），不写入仓库、不打印。
"""
import argparse
import json
import multiprocessing as mp
import os
import pathlib
import random
import time
import urllib.error
import urllib.request

import paho.mqtt.client as mqtt

HERE = pathlib.Path(__file__).parent
DEVICES_FILE = HERE / 'devices.json'
PRODUCT_NAME = '压测产品'
PROPS = [
    {'identifier': 'Ua', 'name': 'A相电压', 'dataType': 'DOUBLE', 'unit': 'V',
     'minValue': 0, 'maxValue': 500, 'decimals': 1},
    {'identifier': 'Ia', 'name': 'A相电流', 'dataType': 'DOUBLE', 'unit': 'A',
     'minValue': 0, 'maxValue': 100, 'decimals': 2},
    {'identifier': 'P', 'name': '有功功率', 'dataType': 'DOUBLE', 'unit': 'kW',
     'minValue': 0, 'maxValue': 1000, 'decimals': 2},
]


def load_config():
    """读取同目录的 config.json；缺失时给出可操作的提示而不是堆栈。"""
    f = HERE / 'config.json'
    if not f.exists():
        raise SystemExit(f'缺少 {f}。请复制 config.example.json 为 config.json 并填入真实值。')
    return json.loads(f.read_text(encoding='utf-8'))


class Api:
    """最小 REST 客户端：登录、按需处理强制改密码、发起增删改查。"""

    def __init__(self, base):
        self.base = base.rstrip('/')
        self.token = None

    def call(self, method, path, body=None):
        req = urllib.request.Request(
            self.base + '/api/v1' + path,
            data=None if body is None else json.dumps(body).encode(),
            headers={'Content-Type': 'application/json',
                     **({'Authorization': 'Bearer ' + self.token} if self.token else {})},
            method=method)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                data, status = r.read(), r.status
        except urllib.error.HTTPError as e:
            data, status = e.read(), e.code
        if status >= 400:
            raise RuntimeError(f'{method} {path} -> {status}: {data[:200]!r}')
        return json.loads(data) if data else None

    def login(self, user, password):
        """登录；账号带 mustChangePassword 时自动改一次密码并复用新令牌。"""
        s = self.call('POST', '/auth/login', {'username': user, 'password': password})
        self.token = s['accessToken']
        if s.get('user', {}).get('mustChangePassword'):
            s2 = self.call('PUT', '/me/password', {'oldPassword': password, 'newPassword': password + 'X1'})
            self.token = s2['accessToken']
            print(f'  （账号 {user} 需改密码，已自动改为原密码+X1）')
        return s


def provision(count):
    """建压测产品、物模型与 N 台设备，密钥写入 devices.json。幂等：产品已存在则复用。"""
    cfg = load_config()
    api = Api(cfg['api'])
    api.login(cfg['admin_username'], cfg['admin_password'])
    products = api.call('GET', '/products?size=100')['items']
    p = next((x for x in products if x['name'] == PRODUCT_NAME), None)
    if p is None:
        p = api.call('POST', '/products', {'name': PRODUCT_NAME, 'description': '压测用，可随时删除',
                                           'codec': 'standard-json'})
        for prop in PROPS:
            api.call('POST', f'/products/{p["productKey"]}/properties', prop)
        print(f'  已建产品 {p["productKey"]} 与 {len(PROPS)} 个测点')
    else:
        print(f'  复用已有产品 {p["productKey"]}')
    devices, t0 = [], time.time()
    for i in range(count):
        d = api.call('POST', '/devices', {'productKey': p['productKey'], 'name': f'压测设备{i + 1:05d}'})
        devices.append({'deviceKey': d['device']['deviceKey'], 'secret': d['deviceSecret'],
                        'productKey': p['productKey']})
        if (i + 1) % 100 == 0:
            print(f'  已建 {i + 1}/{count} 台  ({time.time() - t0:.0f}s)')
    DEVICES_FILE.write_text(json.dumps(devices), encoding='utf-8')
    print(f'完成：{len(devices)} 台，密钥已存入 {DEVICES_FILE.name}（不在版本控制内）')


def _worker(devices, host, port, rate, seconds, out):
    """一个发压进程：持有自己那批设备的连接，独立运行 paho 事件循环。

    每进程只用一个 paho 网络线程（loop_start），发布在本进程主循环里按时间片驱动，
    避免上一版「每设备一线程」的调度开销。
    """
    sent = acked = errors = 0
    lat = []
    pending = {}

    def on_pub(client, userdata, mid, *a):
        nonlocal acked
        t = pending.pop((id(client), mid), None)
        if t is not None:
            acked += 1
            if len(lat) < 50000:
                lat.append((time.time() - t) * 1000)

    clients = []
    for d in devices:
        cl = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=d['deviceKey'], protocol=mqtt.MQTTv5)
        cl.username_pw_set(d['deviceKey'], d['secret'])
        # paho 对 QoS>0 默认只允许 20 条在途，服务端一变慢就会在客户端侧静默限流，
        # 把服务端的瓶颈伪装成客户端的。压测要测服务端上限，所以放宽并禁用本地丢弃。
        cl.max_inflight_messages_set(1000)
        cl.max_queued_messages_set(0)
        cl.on_publish = on_pub
        try:
            cl.connect(host, port, keepalive=60)
            cl.loop_start()
            clients.append((cl, d))
        except Exception:
            errors += 1
    if not clients:
        out.put({'sent': 0, 'acked': 0, 'errors': errors, 'lat': [], 'conns': 0})
        return

    interval = 1.0 / rate
    # 每台设备错开起始相位，避免同一进程内所有设备在同一时刻齐发
    nxt = [time.time() + random.random() * interval for _ in clients]
    deadline = time.time() + seconds
    while time.time() < deadline:
        now = time.time()
        due = [i for i, t in enumerate(nxt) if t <= now]
        if not due:
            time.sleep(min(0.002, max(0.0, min(nxt) - now)))
            continue
        for i in due:
            cl, d = clients[i]
            nxt[i] += interval
            payload = json.dumps({'ts': int(now * 1000), 'params': {
                'Ua': round(random.uniform(215, 235), 1),
                'Ia': round(random.uniform(0, 60), 2),
                'P': round(random.uniform(0, 800), 2)}})
            try:
                info = cl.publish(f'conduit/{d["productKey"]}/{d["deviceKey"]}/up/property', payload, qos=1)
                pending[(id(cl), info.mid)] = time.time()
                sent += 1
            except Exception:
                errors += 1
    time.sleep(2)
    for cl, _ in clients:
        try:
            cl.loop_stop()
            cl.disconnect()
        except Exception:
            pass
    out.put({'sent': sent, 'acked': acked, 'errors': errors, 'lat': lat, 'conns': len(clients)})


def run(count, rate, seconds, workers):
    """多进程发压。workers 默认取 CPU 核数，每进程均分设备。"""
    cfg = load_config()
    devices = json.loads(DEVICES_FILE.read_text(encoding='utf-8'))[:count]
    if len(devices) < count:
        raise SystemExit(f'devices.json 只有 {len(devices)} 台，不足 {count} 台；先跑 provision')
    workers = workers or min(os.cpu_count() or 4, count)
    chunks = [devices[i::workers] for i in range(workers)]
    out = mp.Queue()
    procs = [mp.Process(target=_worker, args=(ch, cfg['mqtt_host'], int(cfg['mqtt_port']), rate, seconds, out),
                        daemon=True) for ch in chunks if ch]
    print(f'{len(devices)} 台设备 × {rate}/秒 = 目标 {len(devices) * rate:,.0f}/秒，'
          f'{len(procs)} 个进程，持续 {seconds} 秒')
    t0 = time.time()
    for p in procs:
        p.start()
    res = [out.get() for _ in procs]
    for p in procs:
        p.join(timeout=30)
    el = time.time() - t0
    sent = sum(r['sent'] for r in res)
    acked = sum(r['acked'] for r in res)
    errors = sum(r['errors'] for r in res)
    conns = sum(r['conns'] for r in res)
    lat = sorted(x for r in res for x in r['lat'])
    qt = lambda f: lat[min(len(lat) - 1, int(len(lat) * f))] if lat else 0.0
    print('\n===== 结果 =====')
    print(f'进程数 / 连接数 : {len(procs)} / {conns}')
    print(f'目标速率        : {len(devices) * rate:,.0f}/秒')
    print(f'实际发送        : {sent:,} 条，平均 {sent / el:,.0f}/秒')
    print(f'已确认 (PUBACK) : {acked:,} 条，平均 {acked / el:,.0f}/秒  （未确认 {sent - acked:,}）')
    print(f'PUBACK 时延     : p50 {qt(.5):.1f}ms  p95 {qt(.95):.1f}ms  p99 {qt(.99):.1f}ms  max {lat[-1] if lat else 0:.1f}ms')
    print(f'客户端错误      : {errors}')
    print('\n注意：PUBACK 只表示 EMQX 已接收，不表示已入库。过载时 EMQX 会先确认再丢弃，')
    print('      真实入库能力请以服务端的 conduit_pipeline_processed_total 为准。')


if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    pp = sub.add_parser('provision')
    pp.add_argument('--devices', type=int, required=True)
    rp = sub.add_parser('run')
    rp.add_argument('--devices', type=int, required=True)
    rp.add_argument('--rate', type=float, required=True, help='每台设备每秒发布条数')
    rp.add_argument('--seconds', type=int, default=180)
    rp.add_argument('--workers', type=int, default=0, help='发压进程数，默认取 CPU 核数')
    a = ap.parse_args()
    if a.cmd == 'provision':
        provision(a.devices)
    else:
        run(a.devices, a.rate, a.seconds, a.workers)
