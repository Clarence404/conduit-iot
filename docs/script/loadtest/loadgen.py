"""Conduit IoT 压测负载生成器（多进程版）。

为什么是多进程：上一版用「每设备一个线程」，600 线程时受 GIL 与调度开销限制，
约 3,500 条/秒即出现颠簸，而且**目标速率调得越高实际打出越少**
（实测目标 8,000 打出 2,857、目标 10,000 只打出 1,679）。
于是系统的真实容量天花板测不出来——测到的是负载端的极限。
改为多进程后每个进程独立持有一批设备与各自的 paho 事件循环，绕开 GIL。

三个子命令：
  provision --devices N        经 REST 建压测产品并把设备补齐到 N 台，明文密钥存入 devices.json
  refresh                      重置 devices.json 里全部设备的密钥并回写（**每次压测前必跑**，见下）
  run --devices N --rate R --seconds S [--workers W] [--fleet]
                               以 N 条 MQTT 连接按每设备每秒 R 条发布，统计吞吐与 PUBACK 时延；
                               --fleet 为海量低频设备模式（如 --devices 20000 --rate 0.1）

**必须先 refresh，否则测出来的全是废数据。** 压测产品带物模型，于是旧版演示模拟器会把它
当成演示产品接管（2026-10-30 起模拟器默认以 SIM_EXCLUDE_PRODUCTS 排除「压测产品」）：`SIM_TENANTS=*` 让它扫描所有租户的物模型产品，并给每个产品的前
`SIM_MAX_DEVICES_PER_PRODUCT` 台设备重置密钥（压测机上是 100）。被重置的那些设备在
devices.json 里的密钥就失效了。

失效的方式极其隐蔽：paho 的 `connect()` 只发起 TCP，CONNACK 在网络线程里才到，
所以鉴权被拒**不会抛异常**——脚本照样报「连接数 600」「客户端错误 0」，而那 100 台的
publish 全部堆进 paho 本地队列、一条也发不出去，断开时静默丢弃。

2026-10-29 实测后果：名义目标 10,000/秒实际只喂进 8,333，而 EMQX 恰好收到 8,334。
由此误判出一道并不存在的「约 8,300 条/秒入口天花板」，并写进了测试报告与架构决定。
特征是**接入量永远恰好是名义目标的 5/6**——真正的容量上限是绝对值，不会随你给多少而
按比例变化。看到固定比值，先查负载端。

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


def provision(count, concurrency=8):
    """补齐压测产品与设备到 N 台，密钥写入 devices.json。幂等：产品已存在则复用，已有设备保留。

    devices.json 中已有的设备不动，只新建差额（名称接着编号），多线程并发创建；
    令牌 15 分钟过期，遇 401 自动重新登录。每建 500 台落盘一次，中断后重跑会接着补。
    """
    import threading
    from concurrent.futures import ThreadPoolExecutor
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
    devices = json.loads(DEVICES_FILE.read_text(encoding='utf-8')) if DEVICES_FILE.exists() else []
    devices = [d for d in devices if d['productKey'] == p['productKey']]
    need = count - len(devices)
    if need <= 0:
        print(f'devices.json 已有 {len(devices)} 台，不少于 {count} 台，无需新建')
        return
    lock = threading.Lock()

    def call(method, path, body=None):
        try:
            return api.call(method, path, body)
        except RuntimeError as e:
            if '-> 401' not in str(e):
                raise
            with lock:
                api.login(cfg['admin_username'], cfg['admin_password'])
            return api.call(method, path, body)

    def create(i):
        d = call('POST', '/devices', {'productKey': p['productKey'], 'name': f'压测设备{i:05d}'})
        return {'deviceKey': d['device']['deviceKey'], 'secret': d['deviceSecret'], 'productKey': p['productKey']}

    start, t0 = len(devices) + 1, time.time()
    with ThreadPoolExecutor(concurrency) as pool:
        for n, d in enumerate(pool.map(create, range(start, start + need)), 1):
            devices.append(d)
            if n % 500 == 0 or n == need:
                DEVICES_FILE.write_text(json.dumps(devices), encoding='utf-8')
                print(f'  已建 {n}/{need} 台  ({time.time() - t0:.0f}s)')
    print(f'完成：共 {len(devices)} 台，密钥已存入 {DEVICES_FILE.name}（不在版本控制内）')


def refresh():
    """重置 devices.json 中全部设备的密钥并回写，抵消演示模拟器的密钥接管。

    每次压测前都要跑：模拟器可能已经重置过其中一部分设备的密钥（见模块说明），
    而这种失效在运行时是静默的。重置是幂等的，重复跑无害。
    """
    cfg = load_config()
    api = Api(cfg['api'])
    api.login(cfg['admin_username'], cfg['admin_password'])
    devices = json.loads(DEVICES_FILE.read_text(encoding='utf-8'))
    t0, failed = time.time(), []
    for i, d in enumerate(devices):
        try:
            s = api.call('POST', f'/devices/{d["deviceKey"]}/reset-secret')
            d['secret'] = s['deviceSecret']
        except Exception as e:
            failed.append((d['deviceKey'], str(e)[:80]))
        if (i + 1) % 100 == 0:
            print(f'  已重置 {i + 1}/{len(devices)} 台  ({time.time() - t0:.0f}s)')
    DEVICES_FILE.write_text(json.dumps(devices), encoding='utf-8')
    if failed:
        # 不静默跳过：任何一台失败都会让后续测量少喂一份负载，必须让人看见
        print(f'**{len(failed)} 台重置失败**，这些设备会在压测中静默鉴权失败：')
        for k, e in failed[:10]:
            print(f'  {k}: {e}')
        raise SystemExit('密钥未全部刷新，先解决上面的失败再压测。')
    print(f'完成：{len(devices)} 台密钥已全部刷新')


def _worker(devices, host, port, rate, seconds, out):
    """一个发压进程：持有自己那批设备的连接，独立运行 paho 事件循环。

    每进程只用一个 paho 网络线程（loop_start），发布在本进程主循环里按时间片驱动，
    避免上一版「每设备一线程」的调度开销。
    """
    sent = acked = errors = 0
    lat = []
    pending = {}
    # CONNACK 成功的客户端 id 集合。paho 的 connect() 只发起 TCP，鉴权被拒不会抛异常，
    # 所以必须等 CONNACK 才知道这条连接到底能不能用——否则失败的设备会静默地一条都发不出去。
    accepted = set()
    refused = {}

    def on_conn(client, userdata, flags, reason, *a):
        code = getattr(reason, 'value', reason)
        if code == 0:
            accepted.add(id(client))
        else:
            refused[id(client)] = str(reason)

    def on_pub(client, userdata, mid, *a):
        nonlocal acked
        t = pending.pop((id(client), mid), None)
        if t is not None:
            acked += 1
            if len(lat) < 50000:
                lat.append((time.time() - t) * 1000)

    opened = []
    for d in devices:
        cl = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=d['deviceKey'], protocol=mqtt.MQTTv5)
        cl.username_pw_set(d['deviceKey'], d['secret'])
        # paho 对 QoS>0 默认只允许 20 条在途，服务端一变慢就会在客户端侧静默限流，
        # 把服务端的瓶颈伪装成客户端的。压测要测服务端上限，所以放宽并禁用本地丢弃。
        cl.max_inflight_messages_set(1000)
        cl.max_queued_messages_set(0)
        cl.on_connect = on_conn
        cl.on_publish = on_pub
        try:
            cl.connect(host, port, keepalive=60)
            cl.loop_start()
            opened.append((cl, d))
        except Exception:
            errors += 1

    # 等 CONNACK 到齐（最多 10 秒），只把鉴权通过的连接纳入发压
    deadline_conn = time.time() + 10
    while time.time() < deadline_conn and len(accepted) + len(refused) < len(opened):
        time.sleep(0.1)
    clients = [(cl, d) for cl, d in opened if id(cl) in accepted]
    rejected = [d['deviceKey'] for cl, d in opened if id(cl) not in accepted]
    if not clients:
        out.put({'sent': 0, 'acked': 0, 'errors': errors, 'lat': [], 'conns': 0,
                 'rejected': rejected, 'reasons': sorted(set(refused.values()))})
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
    out.put({'sent': sent, 'acked': acked, 'errors': errors, 'lat': lat, 'conns': len(clients),
             'rejected': rejected, 'reasons': sorted(set(refused.values()))})


def _fleet_worker(devices, host, port, rate, seconds, connect_rate, connect_timeout, out):
    """海量低频设备的发压进程：单个事件循环驱动数千条连接，不为每条连接开线程。

    _worker 给每条连接 loop_start()，每个客户端一个线程外加一对唤醒用的回环套接字（Windows 上
    占用临时端口），几万台设备时线程、内存与端口都会先于服务端耗尽。这里改由本进程自己 select
    所有连接的套接字并调用 loop_read / loop_write / loop_misc，publish 时 paho 会直接写出。
    Windows 的 select 每次最多 512 个套接字，所以分组轮询。
    """
    import heapq
    import select
    sent = acked = errors = 0
    lat, pending, accepted, refused = [], {}, set(), {}

    def on_conn(client, userdata, flags, reason, *a):
        code = getattr(reason, 'value', reason)
        if code == 0:
            accepted.add(id(client))
        else:
            refused[id(client)] = str(reason)

    def on_pub(client, userdata, mid, *a):
        nonlocal acked
        t = pending.pop((id(client), mid), None)
        if t is not None:
            acked += 1
            if len(lat) < 50000:
                lat.append((time.time() - t) * 1000)

    opened = []

    def pump(timeout):
        """轮询全部连接一次：可读的读、有待发数据的写。"""
        socks = [(cl.socket(), cl) for cl, _ in opened]
        socks = [(sk, cl) for sk, cl in socks if sk is not None]
        if not socks:
            time.sleep(timeout)
            return
        for i in range(0, len(socks), 500):
            chunk = dict(socks[i:i + 500])
            w = [sk for sk, cl in chunk.items() if cl.want_write()]
            try:
                r, w, _ = select.select(list(chunk), w, [], timeout if i == 0 else 0)
            except OSError:
                continue
            for sk in r:
                chunk[sk].loop_read(10)
            for sk in w:
                chunk[sk].loop_write()

    # 按 connect_rate 匀速建连，边建边处理 CONNACK，避免几万台同时打到鉴权回调
    t_start = time.time()
    for n, d in enumerate(devices):
        cl = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=d['deviceKey'], protocol=mqtt.MQTTv5)
        cl.username_pw_set(d['deviceKey'], d['secret'])
        cl.max_inflight_messages_set(1000)
        cl.max_queued_messages_set(0)
        cl.on_connect = on_conn
        cl.on_publish = on_pub
        try:
            cl.connect(host, port, keepalive=60)
            opened.append((cl, d))
        except Exception:
            errors += 1
        lag = t_start + (n + 1) / connect_rate - time.time()
        pump(max(0.0, lag))
    deadline_conn = time.time() + connect_timeout
    last_misc = 0.0
    while time.time() < deadline_conn and len(accepted) + len(refused) < len(opened):
        pump(0.05)
        if time.time() - last_misc > 1:
            for cl, _ in opened:
                cl.loop_misc()
            last_misc = time.time()
    clients = [(cl, d) for cl, d in opened if id(cl) in accepted]
    rejected = [d['deviceKey'] for cl, d in opened if id(cl) not in accepted]
    connect_seconds = time.time() - t_start

    interval = 1.0 / rate
    t0 = time.time()
    heap = [(t0 + random.random() * interval, i) for i in range(len(clients))]
    heapq.heapify(heap)
    deadline = t0 + seconds
    disconnects = 0
    while time.time() < deadline:
        now = time.time()
        while heap and heap[0][0] <= now:
            due, i = heapq.heappop(heap)
            heapq.heappush(heap, (due + interval, i))
            cl, d = clients[i]
            payload = json.dumps({'ts': int(now * 1000), 'params': {
                'Ua': round(random.uniform(215, 235), 1),
                'Ia': round(random.uniform(0, 60), 2),
                'P': round(random.uniform(0, 800), 2)}})
            try:
                info = cl.publish(f'conduit/{d["productKey"]}/{d["deviceKey"]}/up/property', payload, qos=1)
                if info.rc == 0:
                    pending[(id(cl), info.mid)] = time.time()
                    sent += 1
                else:
                    errors += 1
            except Exception:
                errors += 1
        pump(min(0.05, max(0.0, heap[0][0] - time.time())) if heap else 0.05)
        if now - last_misc > 1:
            for cl, _ in clients:
                if cl.loop_misc() != 0:
                    disconnects += 1
            last_misc = now
    end = time.time() + 2
    while time.time() < end:
        pump(0.05)
    for cl, _ in opened:
        try:
            cl.disconnect()
        except Exception:
            pass
    out.put({'sent': sent, 'acked': acked, 'errors': errors, 'lat': lat, 'conns': len(clients),
             'rejected': rejected, 'reasons': sorted(set(refused.values())),
             'connect_seconds': connect_seconds, 'disconnects': disconnects})


def run(count, rate, seconds, workers, fleet=False, connect_rate=200.0, connect_timeout=120):
    """多进程发压。workers 默认取 CPU 核数（fleet 模式按每进程约 2,500 台），每进程均分设备。

    fleet=True 用单事件循环的 _fleet_worker，适合海量低频设备（如 2 万台每 10 秒一条）；
    connect_rate 为每个进程每秒新建连接数。
    """
    cfg = load_config()
    devices = json.loads(DEVICES_FILE.read_text(encoding='utf-8'))[:count]
    if len(devices) < count:
        raise SystemExit(f'devices.json 只有 {len(devices)} 台，不足 {count} 台；先跑 provision')
    if fleet:
        workers = workers or max(1, -(-count // 2500))
    workers = workers or min(os.cpu_count() or 4, count)
    chunks = [devices[i::workers] for i in range(workers)]
    out = mp.Queue()
    host, port = cfg['mqtt_host'], int(cfg['mqtt_port'])
    if fleet:
        procs = [mp.Process(target=_fleet_worker,
                            args=(ch, host, port, rate, seconds, connect_rate, connect_timeout, out),
                            daemon=True) for ch in chunks if ch]
    else:
        procs = [mp.Process(target=_worker, args=(ch, host, port, rate, seconds, out), daemon=True)
                 for ch in chunks if ch]
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
    rejected = [k for r in res for k in r.get('rejected', [])]
    lat = sorted(x for r in res for x in r['lat'])
    if rejected:
        # 绝不把「部分设备没连上」当成可以接受的噪声：少一台就少喂一份负载，
        # 而实际供给低于名义目标会让人把「供给不足」误读成「系统到顶」。
        print(f'\n**{len(rejected)} / {len(devices)} 台设备未通过鉴权，本次结果作废。**')
        print(f'   原因：{"; ".join(sorted({x for r in res for x in r.get("reasons", [])})) or "未收到 CONNACK"}')
        print(f'   示例：{", ".join(rejected[:5])}')
        print('   多半是演示模拟器重置了这些设备的密钥，先跑 `loadgen.py refresh` 再压测。')
        raise SystemExit(2)
    qt = lambda f: lat[min(len(lat) - 1, int(len(lat) * f))] if lat else 0.0
    print('\n===== 结果 =====')
    print(f'进程数 / 连接数 : {len(procs)} / {conns}')
    if fleet:
        print(f'建连耗时        : 最慢进程 {max(r["connect_seconds"] for r in res):.0f} 秒')
        print(f'运行中断线      : {sum(r["disconnects"] for r in res)} 次')
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
    pp.add_argument('--devices', type=int, required=True, help='补齐到的总台数')
    pp.add_argument('--concurrency', type=int, default=8)
    sub.add_parser('refresh')
    rp = sub.add_parser('run')
    rp.add_argument('--devices', type=int, required=True)
    rp.add_argument('--rate', type=float, required=True, help='每台设备每秒发布条数')
    rp.add_argument('--seconds', type=int, default=180)
    rp.add_argument('--workers', type=int, default=0, help='发压进程数，默认取 CPU 核数')
    rp.add_argument('--fleet', action='store_true', help='海量低频设备模式：单事件循环驱动数千连接')
    rp.add_argument('--connect-rate', type=float, default=200.0, help='fleet 模式每进程每秒建连数')
    rp.add_argument('--connect-timeout', type=int, default=120, help='fleet 模式建连后等 CONNACK 的秒数')
    a = ap.parse_args()
    if a.cmd == 'provision':
        provision(a.devices, a.concurrency)
    elif a.cmd == 'refresh':
        refresh()
    else:
        run(a.devices, a.rate, a.seconds, a.workers, a.fleet, a.connect_rate, a.connect_timeout)
