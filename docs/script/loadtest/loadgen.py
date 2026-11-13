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

设备画像（测法四，只在 --fleet 下生效）：
  --points M                   每条报文带测点表的前 M 个测点（1～30，默认 3，与旧版报文相同）
  --mix "60:0.7,15:0.25,1:0.05" 混合上报间隔：秒数:占比，占比之和为 1；给出时 --rate 不再使用，
                               每台设备按序号确定性地分到一个间隔，多次运行同一台设备的间隔不变
  --id-ratio F                 报文带 `id`（可靠上报）的设备占比，同样按序号确定性分配

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



def _prop(identifier, name, unit, lo, hi, decimals, gen_lo=None, gen_hi=None):
    """一个 DOUBLE 测点的物模型定义；gen_lo / gen_hi 为发压时取值范围（缺省同上下限）。"""
    return {'identifier': identifier, 'name': name, 'dataType': 'DOUBLE', 'unit': unit,
            'minValue': lo, 'maxValue': hi, 'decimals': decimals,
            '_gen': (lo if gen_lo is None else gen_lo, hi if gen_hi is None else gen_hi)}


# 测点表：一块三相电表的 30 个常见测点。前 3 个（Ua、Ia、P）是旧版报文的全部测点，顺序不可改：
# --points 3 时与旧版报文逐字段相同。所有产品共用 InfluxDB 的 property 表，同名测点在全表必须同一类型，
# 这里全部是 DOUBLE；2026-11-13 核对过表中已有的 Ua/Ub/Uc/Ia/Ib/Ic/P/EPimp 均为 Float，没有冲突。
PROPS = [
    _prop('Ua', 'A相电压', 'V', 0, 500, 1, 215, 235),
    _prop('Ia', 'A相电流', 'A', 0, 100, 2, 0, 60),
    _prop('P', '有功功率', 'kW', 0, 1000, 2, 0, 800),
    _prop('Ub', 'B相电压', 'V', 0, 500, 1, 215, 235),
    _prop('Uc', 'C相电压', 'V', 0, 500, 1, 215, 235),
    _prop('Uab', 'AB线电压', 'V', 0, 800, 1, 375, 405),
    _prop('Ubc', 'BC线电压', 'V', 0, 800, 1, 375, 405),
    _prop('Uca', 'CA线电压', 'V', 0, 800, 1, 375, 405),
    _prop('Ib', 'B相电流', 'A', 0, 100, 2, 0, 60),
    _prop('Ic', 'C相电流', 'A', 0, 100, 2, 0, 60),
    _prop('In', '零线电流', 'A', 0, 100, 2, 0, 5),
    _prop('Pa', 'A相有功功率', 'kW', 0, 400, 2, 0, 270),
    _prop('Pb', 'B相有功功率', 'kW', 0, 400, 2, 0, 270),
    _prop('Pc', 'C相有功功率', 'kW', 0, 400, 2, 0, 270),
    _prop('Qa', 'A相无功功率', 'kvar', -400, 400, 2, -50, 50),
    _prop('Qb', 'B相无功功率', 'kvar', -400, 400, 2, -50, 50),
    _prop('Qc', 'C相无功功率', 'kvar', -400, 400, 2, -50, 50),
    _prop('Q', '总无功功率', 'kvar', -1000, 1000, 2, -150, 150),
    _prop('Sa', 'A相视在功率', 'kVA', 0, 400, 2, 0, 280),
    _prop('Sb', 'B相视在功率', 'kVA', 0, 400, 2, 0, 280),
    _prop('Sc', 'C相视在功率', 'kVA', 0, 400, 2, 0, 280),
    _prop('S', '总视在功率', 'kVA', 0, 1000, 2, 0, 830),
    _prop('PFa', 'A相功率因数', '', -1, 1, 3, 0.85, 1),
    _prop('PFb', 'B相功率因数', '', -1, 1, 3, 0.85, 1),
    _prop('PFc', 'C相功率因数', '', -1, 1, 3, 0.85, 1),
    _prop('PF', '总功率因数', '', -1, 1, 3, 0.85, 1),
    _prop('F', '频率', 'Hz', 45, 65, 2, 49.9, 50.1),
    _prop('EPimp', '正向有功电能', 'kWh', 0, 1e9, 2, 0, 1e6),
    _prop('EPexp', '反向有功电能', 'kWh', 0, 1e9, 2, 0, 1e4),
    _prop('EQimp', '正向无功电能', 'kvarh', 0, 1e9, 2, 0, 1e5),
]
MAX_POINTS = len(PROPS)


def _params(points):
    """按测点表的前 points 个测点生成一条报文的 params。"""
    return {p['identifier']: round(random.uniform(*p['_gen']), p['decimals']) for p in PROPS[:points]}


def parse_mix(text):
    """解析混合上报间隔 "秒:占比,秒:占比"，返回 [(间隔秒, 占比)]；占比之和须为 1（容差 1e-6）。"""
    mix = []
    for part in text.split(','):
        sec, frac = part.split(':')
        sec, frac = float(sec), float(frac)
        if sec <= 0 or frac < 0:
            raise SystemExit(f'--mix 非法：{part}（间隔须为正、占比非负）')
        mix.append((sec, frac))
    if abs(sum(f for _, f in mix) - 1) > 1e-6:
        raise SystemExit(f'--mix 的占比之和须为 1，当前为 {sum(f for _, f in mix)}')
    return mix


def mix_rate(mix):
    """混合画像下每台设备的平均速率（条/秒）。"""
    return sum(f / sec for sec, f in mix)


def _frac(index, salt):
    """设备序号 → [0,1) 的确定性伪随机数：同一台设备每次运行分到的间隔与是否带 id 都不变，且两者互不相关。"""
    import hashlib
    h = hashlib.blake2b(f'{salt}:{index}'.encode(), digest_size=8).digest()
    return int.from_bytes(h, 'big') / 2 ** 64


def assign_profile(count, offset, rate, mix, id_ratio):
    """给 devices.json 中 [offset, offset+count) 这段设备各分配（上报间隔秒, 是否带 id）。

    不给 mix 时所有设备间隔都是 1/rate。按设备在 devices.json 中的绝对序号计算，多台发压机分片时结果一致。
    """
    out = []
    for i in range(offset, offset + count):
        if mix:
            x, acc, interval = _frac(i, 'mix'), 0.0, mix[-1][0]
            for sec, f in mix:
                acc += f
                if x < acc:
                    interval = sec
                    break
        else:
            interval = 1.0 / rate
        out.append((interval, _frac(i, 'id') < id_ratio))
    return out


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


def _items(resp):
    """接口返回可能是数组，也可能是分页对象 {items: [...]}；统一成列表。"""
    return resp.get('items', []) if isinstance(resp, dict) else (resp or [])


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
        print(f'  已建产品 {p["productKey"]}')
    else:
        print(f'  复用已有产品 {p["productKey"]}')
    # 补齐测点表中缺少的测点（已有的不动）：测点表扩充后，旧产品与其设备照常可用
    have = {x['identifier'] for x in _items(api.call('GET', f'/products/{p["productKey"]}/properties'))}
    added = 0
    for prop in PROPS:
        if prop['identifier'] not in have:
            api.call('POST', f'/products/{p["productKey"]}/properties',
                     {k: v for k, v in prop.items() if not k.startswith('_')})
            added += 1
    print(f'  测点 {len(PROPS)} 个，本次补建 {added} 个')
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


LAT_SAMPLES = 50000


def _sample(lat, n, value):
    """时延蓄水池抽样：在已确认的 n 条里等概率保留 LAT_SAMPLES 个。

    只留前 5 万个会让高档位（每秒上万条）的分位数只反映开头几秒，正好错过积压形成之后的长尾。
    """
    if len(lat) < LAT_SAMPLES:
        lat.append(value)
    else:
        j = random.randrange(n)
        if j < LAT_SAMPLES:
            lat[j] = value


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
            _sample(lat, acked, (time.time() - t) * 1000)

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


def _fleet_worker(devices, host, port, rate, seconds, connect_rate, connect_timeout, out, profile=None, points=3):
    """海量低频设备的发压进程：单个事件循环驱动数千条连接，不为每条连接开线程。

    _worker 给每条连接 loop_start()，每个客户端一个线程外加一对唤醒用的回环套接字（Windows 上
    占用临时端口），几万台设备时线程、内存与端口都会先于服务端耗尽。这里改由本进程自己 select
    所有连接的套接字并调用 loop_read / loop_write / loop_misc，publish 时 paho 会直接写出。
    Windows 的 select 每次最多 512 个套接字，所以分组轮询。

    profile 与 devices 一一对应，为 [(上报间隔秒, 是否带 id)]；为 None 时全部按 1/rate、不带 id（旧行为）。
    points 为每条报文的测点数（取测点表前 points 个）。
    """
    import heapq
    import select
    sent = acked = errors = 0
    lat, pending, accepted, refused = [], {}, set(), {}
    # 运行中掉线的设备（按设备去重；收尾时主动断开的不算）
    dropped, closing = set(), False

    prof = {d['deviceKey']: (profile[n] if profile else (1.0 / rate, False)) for n, d in enumerate(devices)}
    ack_topic = {}      # id(client) → 回执主题；只有带 id 的设备订阅，像真实设备一样接收平台回执
    acks = 0

    def on_conn(client, userdata, flags, reason, *a):
        code = getattr(reason, 'value', reason)
        if code == 0:
            accepted.add(id(client))
            t = ack_topic.get(id(client))
            if t:
                client.subscribe(t, qos=0)
        else:
            refused[id(client)] = str(reason)

    def on_msg(client, userdata, msg):
        nonlocal acks
        acks += 1

    disc_reasons, disc_times = {}, []

    def on_disc(client, userdata=None, flags=None, reason=None, *a):
        if not closing and id(client) in accepted:
            dropped.add(id(client))
            key = str(reason)
            disc_reasons[key] = disc_reasons.get(key, 0) + 1
            disc_times.append(time.time())

    def on_pub(client, userdata, mid, *a):
        nonlocal acked
        t = pending.pop((id(client), mid), None)
        if t is not None:
            acked += 1
            _sample(lat, acked, (time.time() - t) * 1000)

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
        cl.on_disconnect = on_disc
        cl.on_publish = on_pub
        if prof[d['deviceKey']][1]:
            ack_topic[id(cl)] = f'conduit/{d["productKey"]}/{d["deviceKey"]}/down/ack'
            cl.on_message = on_msg
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
    # 首次被拒的设备像真实设备一样 2 秒后重连一次：EMQX 到 server 的鉴权长连接偶发「半开」，请求超时、
    # 被判未授权，约 10 秒后 EMQX 自行重建连接（2026-10-30 实测，原因未查明）。首次被拒数照实上报。
    retry = [(cl, d) for cl, d in opened if id(cl) not in accepted]
    if retry:
        time.sleep(2)
        for cl, _ in retry:
            refused.pop(id(cl), None)
            try:
                cl.reconnect()
            except Exception:
                pass
        deadline_conn = time.time() + connect_timeout
        while time.time() < deadline_conn and any(id(cl) not in accepted and id(cl) not in refused for cl, _ in retry):
            pump(0.05)
            if time.time() - last_misc > 1:
                for cl, _ in opened:
                    cl.loop_misc()
                last_misc = time.time()
    clients = [(cl, d) for cl, d in opened if id(cl) in accepted]
    rejected = [d['deviceKey'] for cl, d in opened if id(cl) not in accepted]
    connect_seconds = time.time() - t_start

    intervals = [prof[d['deviceKey']][0] for _, d in clients]
    with_id = [prof[d['deviceKey']][1] for _, d in clients]
    seq = [0] * len(clients)
    t0 = time.time()
    cpu0 = time.process_time()   # 本进程稳态期间自身占用的 CPU，用来判断发压进程是否打满（与整机 CPU 区分）
    heap = [(t0 + random.random() * intervals[i], i) for i in range(len(clients))]
    heapq.heapify(heap)
    deadline = t0 + seconds
    # 本进程事件循环的停顿：两次循环之间隔太久说明发压端自己卡住（不读连接时 PUBACK 堆在套接字里，
    # EMQX 发不出去，send_timeout 到期就会主动断开这条连接）
    prev_iter, max_gap, max_gap_at, gaps_over_1s = t0, 0.0, None, 0
    while time.time() < deadline:
        now = time.time()
        gap = now - prev_iter
        prev_iter = now
        if gap > max_gap:
            max_gap, max_gap_at = gap, now
        if gap > 1:
            gaps_over_1s += 1
        while heap and heap[0][0] <= now:
            due, i = heapq.heappop(heap)
            heapq.heappush(heap, (due + intervals[i], i))
            cl, d = clients[i]
            msg = {'ts': int(now * 1000), 'params': _params(points)}
            if with_id[i]:
                # 可靠上报：带 id 即启用，transport 持久接收后向设备的 down/ack 回 QoS 0 回执（连接时已订阅，收到即计数）
                seq[i] += 1
                msg = {'id': f'{d["deviceKey"]}-{int(t0)}-{seq[i]}', **msg}
            payload = json.dumps(msg)
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
                cl.loop_misc()   # 保活心跳与超时检测；掉线经 on_disconnect 记入 dropped
            last_misc = now
    t_load_end = time.time()
    cpu_busy = (time.process_time() - cpu0) / max(1e-6, t_load_end - t0)
    end = time.time() + 2
    while time.time() < end:
        pump(0.05)
    closing = True
    for cl, _ in opened:
        try:
            cl.disconnect()
        except Exception:
            pass
    out.put({'sent': sent, 'acked': acked, 'errors': errors, 'lat': lat, 'conns': len(clients),
             'rejected': rejected, 'reasons': sorted(set(refused.values())),
             'connect_seconds': connect_seconds, 'disconnects': len(dropped), 'first_rejected': len(retry),
             'id_sent': sum(seq), 'acks': acks,
             't_connected': t0, 't_load_end': t_load_end, 'cpu_busy': cpu_busy,
             'disc_reasons': disc_reasons, 'disc_first': min(disc_times) if disc_times else None,
             'disc_last': max(disc_times) if disc_times else None,
             'max_loop_gap_s': round(max_gap, 3), 'max_loop_gap_at': max_gap_at, 'loop_gaps_over_1s': gaps_over_1s})


def execute(count, rate, seconds, workers=0, fleet=False, connect_rate=200.0, connect_timeout=120,
            offset=0, per_process=2500, quiet=False, points=3, mix=None, id_ratio=0.0):
    """多进程发压并返回汇总结果（不打印结论、不退出），供 run() 与 ladder.py 调用。

    使用 devices.json 中 [offset, offset+count) 这一段设备——多台发压机各取不相交的一段，同一台设备不会被两处同时登录。
    workers 默认取 CPU 核数；fleet 模式默认每 per_process 台一个进程（每台每秒 1 条时宜取 1,000，单进程才跟得上）。
    fleet=True 用单事件循环的 _fleet_worker，适合海量设备；connect_rate 为每个进程每秒新建连接数。
    points / mix / id_ratio 为设备画像（只在 fleet 下生效）：给出 mix 时 rate 被忽略，返回值的 rate 为画像的平均每台速率。

    :return: dict，含 sent / acked / errors / conns / rejected / disconnects / 时延分位（毫秒）/ 起止时间（epoch 秒）
    """
    cfg = load_config()
    devices = json.loads(DEVICES_FILE.read_text(encoding='utf-8'))[offset:offset + count]
    if len(devices) < count:
        raise SystemExit(f'devices.json 从第 {offset} 台起只有 {len(devices)} 台，不足 {count} 台；先跑 provision')
    if not 1 <= points <= MAX_POINTS:
        raise SystemExit(f'--points 须在 1～{MAX_POINTS} 之间')
    if (points != 3 or mix or id_ratio) and not fleet:
        raise SystemExit('--points / --mix / --id-ratio 只在 --fleet 模式下生效')
    profile = assign_profile(count, offset, rate, mix, id_ratio)
    if mix:
        rate = mix_rate(mix)
    if fleet:
        workers = workers or max(1, -(-count // per_process))
    workers = workers or min(os.cpu_count() or 4, count)
    chunks = [devices[i::workers] for i in range(workers)]
    prof_chunks = [profile[i::workers] for i in range(workers)]
    out = mp.Queue()
    host, port = cfg['mqtt_host'], int(cfg['mqtt_port'])
    if fleet:
        procs = [mp.Process(target=_fleet_worker,
                            args=(ch, host, port, rate, seconds, connect_rate, connect_timeout, out, pc, points),
                            daemon=True) for ch, pc in zip(chunks, prof_chunks) if ch]
    else:
        procs = [mp.Process(target=_worker, args=(ch, host, port, rate, seconds, out), daemon=True)
                 for ch in chunks if ch]
    if not quiet:
        print(f'{len(devices)} 台设备 × {rate:g}/秒 = 目标 {len(devices) * rate:,.0f}/秒，'
              f'{len(procs)} 个进程，持续 {seconds} 秒；每条 {points} 个测点'
              + (f'，混合间隔 {mix}' if mix else '') + (f'，带 id 占比 {id_ratio:g}' if id_ratio else ''))
    t0 = time.time()
    for p in procs:
        p.start()
    res = [out.get() for _ in procs]
    for p in procs:
        p.join(timeout=30)
    t_end = time.time()
    lat = sorted(x for r in res for x in r['lat'])
    qt = lambda f: round(lat[min(len(lat) - 1, int(len(lat) * f))], 1) if lat else None
    return {
        'devices': len(devices), 'offset': offset, 'rate': rate, 'seconds': seconds, 'processes': len(procs),
        # 设备画像：每条测点数、混合上报间隔、带 id 的设备数
        'points': points, 'mix': mix, 'with_id': sum(1 for _, w in profile if w),
        # 可靠上报：带 id 发出的条数与收到的平台回执数（回执是 QoS 0，过载时可能少于发出数）
        'id_sent': sum(r.get('id_sent', 0) for r in res), 'acks': sum(r.get('acks', 0) for r in res),
        'sent': sum(r['sent'] for r in res), 'acked': sum(r['acked'] for r in res),
        'errors': sum(r['errors'] for r in res), 'conns': sum(r['conns'] for r in res),
        'rejected': [k for r in res for k in r.get('rejected', [])],
        'reasons': sorted({x for r in res for x in r.get('reasons', [])}),
        'disconnects': sum(r.get('disconnects', 0) for r in res),
        'first_rejected': sum(r.get('first_rejected', 0) for r in res),
        # 诊断：掉线原因（paho 给出的断开原因码）与各进程事件循环的最长停顿
        'disc_reasons': {k: sum(r.get('disc_reasons', {}).get(k, 0) for r in res)
                         for k in {k for r in res for k in r.get('disc_reasons', {})}},
        'disc_first': min((r['disc_first'] for r in res if r.get('disc_first')), default=None),
        'disc_last': max((r['disc_last'] for r in res if r.get('disc_last')), default=None),
        'max_loop_gap_s': max((r.get('max_loop_gap_s', 0) for r in res), default=0),
        'loop_gaps': [(round(r.get('max_loop_gap_s', 0), 2), r.get('max_loop_gap_at'), r.get('loop_gaps_over_1s', 0),
                       r.get('disconnects', 0)) for r in res],
        'connect_seconds': max((r.get('connect_seconds', 0) for r in res), default=0),
        # 发压进程自身的 CPU（稳态期间）：最忙那个进程占单核的百分比，以及全部进程合计占用的核数。
        # 每个 fleet 进程是单线程事件循环，接近 100% 说明发压端自己成了瓶颈。
        'gen_proc_cpu_max': round(100 * max((r['cpu_busy'] for r in res if 'cpu_busy' in r), default=0), 1)
                            if any('cpu_busy' in r for r in res) else None,
        'gen_proc_cores': round(sum(r.get('cpu_busy', 0) for r in res), 2)
                          if any('cpu_busy' in r for r in res) else None,
        'p50_ms': qt(.5), 'p95_ms': qt(.95), 'p99_ms': qt(.99), 'max_ms': round(lat[-1], 1) if lat else None,
        't_start': t0, 't_end': t_end,
        # 全部进程都建连完成、开始发压的时刻，与最后一个进程停止发压的时刻：判定时只看这段稳态
        't_load_start': max((r.get('t_connected', t0) for r in res), default=t0),
        't_load_end': max((r.get('t_load_end', t_end) for r in res), default=t_end),
    }


def run(count, rate, seconds, workers, fleet=False, connect_rate=200.0, connect_timeout=120,
        offset=0, per_process=2500, result_file=None, points=3, mix=None, id_ratio=0.0):
    """命令行 run：发压一次并打印结果；有设备未通过鉴权时以退出码 2 结束。"""
    r = execute(count, rate, seconds, workers, fleet, connect_rate, connect_timeout, offset, per_process,
                points=points, mix=mix, id_ratio=id_ratio)
    rate = r['rate']
    if result_file:
        pathlib.Path(result_file).write_text(json.dumps(r, ensure_ascii=False, indent=1), encoding='utf-8')
    el = r['t_end'] - r['t_start']
    sent, acked, errors, conns, rejected = r['sent'], r['acked'], r['errors'], r['conns'], r['rejected']
    procs = range(r['processes'])
    devices = range(r['devices'])
    if rejected:
        # 绝不把「部分设备没连上」当成可以接受的噪声：少一台就少喂一份负载，
        # 而实际供给低于名义目标会让人把「供给不足」误读成「系统到顶」。
        print(f'\n**{len(rejected)} / {len(devices)} 台设备未通过鉴权，本次结果作废。**')
        print(f'   原因：{"; ".join(r["reasons"]) or "未收到 CONNACK"}')
        print(f'   示例：{", ".join(rejected[:5])}')
        print('   多半是演示模拟器重置了这些设备的密钥，先跑 `loadgen.py refresh` 再压测。')
        raise SystemExit(2)
    ms = lambda v: f'{v:.1f}ms' if v is not None else '-'
    print('\n===== 结果 =====')
    print(f'进程数 / 连接数 : {len(procs)} / {conns}')
    if fleet:
        print(f'建连耗时        : 最慢进程 {r["connect_seconds"]:.0f} 秒')
        print(f'运行中掉线      : {r["disconnects"]} 台')
        print(f'首次鉴权被拒    : {r["first_rejected"]} 台（已重连一次）')
    print(f'目标速率        : {len(devices) * rate:,.0f}/秒')
    print(f'实际发送        : {sent:,} 条，平均 {sent / el:,.0f}/秒')
    print(f'已确认 (PUBACK) : {acked:,} 条，平均 {acked / el:,.0f}/秒  （未确认 {sent - acked:,}）')
    print(f'PUBACK 时延     : p50 {ms(r["p50_ms"])}  p95 {ms(r["p95_ms"])}  p99 {ms(r["p99_ms"])}  max {ms(r["max_ms"])}')
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
    rp.add_argument('--offset', type=int, default=0, help='从 devices.json 第几台开始取（多台发压机各取不相交的一段）')
    rp.add_argument('--per-process', type=int, default=2500, help='fleet 模式每个进程负责的设备数')
    rp.add_argument('--result-file', help='把结果另存为 JSON')
    rp.add_argument('--points', type=int, default=3, help=f'每条报文的测点数（1～{MAX_POINTS}，fleet 模式）')
    rp.add_argument('--mix', help='混合上报间隔，如 60:0.7,15:0.25,1:0.05（给出时忽略 --rate，fleet 模式）')
    rp.add_argument('--id-ratio', type=float, default=0.0, help='带 id 可靠上报的设备占比（fleet 模式）')
    a = ap.parse_args()
    if a.cmd == 'provision':
        provision(a.devices, a.concurrency)
    elif a.cmd == 'refresh':
        refresh()
    else:
        run(a.devices, a.rate, a.seconds, a.workers, a.fleet, a.connect_rate, a.connect_timeout,
            a.offset, a.per_process, a.result_file, a.points, parse_mix(a.mix) if a.mix else None, a.id_ratio)
