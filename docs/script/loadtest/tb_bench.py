"""ThingsBoard CE 对照压测：建设备、按档发压、按与 Conduit 相同的判据核对。

  provision --devices N   用 ThingsBoard 演示租户经 REST 补齐 N 台设备，访问令牌写入 tb-devices.json（不入库）
  run --plan 10000,25000 [--hold 600] [ladder.py 的画像参数 ...]
  run --devices 1000 --rate-plan 1,2,5,10 [--hold 300]
        逐档调用 `ladder.py run --no-check` 发压（LOADGEN_DEVICES / LOADGEN_MQTT_HOST 指向 ThingsBoard），
        每档结束后在应用主机上核对：
          1. 守恒：PUBACK 条数 = ts_kv 中测点 Ua 在本档时间窗的行数（每条报文都含 Ua，故行数 = 入库消息数）；
          2. 积压：发压结束 5 秒后仍未入库的条数不超过 5 秒流量（ThingsBoard 内存队列看不到长度，用入库差代替）；
          3. 无掉线、p99 ≤ 1 秒、结果可信（与 ladder.py 相同）；
        另记排空用时、应用主机整机 CPU、tb-node / tb-postgres 的 CPU 与内存、tb-node 日志中的 ERROR 行数。
        首个不合格的档位即停。

ThingsBoard 部署在应用主机，两套配置，都只在 Conduit 应用停止时启动、彼此也不同时运行：
  - 默认（TB_STACK 不设）：/opt/tb-bench，tb-node 4.3.1.8 单体 + 内存队列 + PostgreSQL 18；
    行数查 PostgreSQL 的 ts_kv，积压用「发压结束 5 秒后未入库的条数」代替；
  - TB_STACK=kc：/opt/tb-bench-kc，官方推荐的高吞吐组合 Kafka 队列 + Cassandra 时序 + PostgreSQL 实体；
    积压取 Kafka 各消费组 lag 之和，排空以 lag 归零为准，行数逐台设备查 Cassandra 的 ts_kv_cf（设备清单 tb-devices-kc.json）。演示租户账号为 ThingsBoard 安装时的默认值，可用环境变量
TB_USER / TB_PASSWORD 覆盖；这套环境只用于对照测试，测完即停。
"""
import argparse
import json
import os
import pathlib
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

HERE = pathlib.Path(__file__).parent
sys.path.insert(0, str(HERE))
import remote  # noqa: E402

KC = os.environ.get('TB_STACK') == 'kc'
TB_DEVICES = HERE / ('tb-devices-kc.json' if KC else 'tb-devices.json')
NODE, PG = ('tbk-node', 'tbk-postgres') if KC else ('tb-node', 'tb-postgres')
CONTAINERS = ('tbk-node', 'tbk-postgres', 'tbk-kafka', 'tbk-cassandra') if KC else ('tb-node', 'tb-postgres')
GEN_PROC_LIMIT = 85
HOST_CPU_LIMIT = 85


def tb_host():
    """ThingsBoard 与 Conduit 应用同机：取 config.json 中 api 的主机名。"""
    return urllib.parse.urlparse(remote.config()['api']).hostname


def tb_url():
    return os.environ.get('TB_URL') or f'http://{tb_host()}:8080'


def login():
    """用演示租户管理员登录，返回 JWT。"""
    body = json.dumps({'username': os.environ.get('TB_USER', 'tenant@thingsboard.org'),
                       'password': os.environ.get('TB_PASSWORD', 'tenant')}).encode()
    req = urllib.request.Request(tb_url() + '/api/auth/login', body, {'Content-Type': 'application/json'})
    return json.loads(urllib.request.urlopen(req, timeout=30).read())['token']


def provision(count, concurrency=16):
    """补齐到 count 台设备（名称「压测设备-NNNNN」，类型 default，访问令牌随机），幂等：已有的保留。"""
    devices = json.loads(TB_DEVICES.read_text(encoding='utf-8')) if TB_DEVICES.exists() else []
    if len(devices) >= count:
        print(f'tb-devices.json 已有 {len(devices)} 台，无需新建')
        return
    token = login()

    def create(i):
        access = uuid.uuid4().hex
        body = json.dumps({'name': f'压测设备-{i:05d}', 'type': 'default'}).encode()
        req = urllib.request.Request(tb_url() + '/api/device?accessToken=' + access, body,
                                     {'Content-Type': 'application/json', 'X-Authorization': 'Bearer ' + token})
        d = json.loads(urllib.request.urlopen(req, timeout=60).read())
        return {'deviceKey': access, 'platform': 'thingsboard', 'name': d['name'], 'id': d['id']['id']}

    t0 = time.time()
    with ThreadPoolExecutor(concurrency) as ex:
        new = list(ex.map(create, range(len(devices) + 1, count + 1)))
    devices += new
    TB_DEVICES.write_text(json.dumps(devices, ensure_ascii=False), encoding='utf-8')
    print(f'新建 {len(new)} 台，共 {len(devices)} 台，用时 {time.time() - t0:.0f} 秒')


def ts_rows(start_ms, end_ms, c, devices=None):
    """测点 Ua 在 [start_ms, end_ms) 的入库行数；查询失败返回 None。kc 模式下逐台查 Cassandra（devices 为本档设备）。"""
    if KC:
        return cassandra_rows(start_ms, end_ms, devices, c)
    sql = ("select count(*) from ts_kv where key = (select key_id from key_dictionary where key = 'Ua') "
           f"and ts >= {int(start_ms)} and ts < {int(end_ms)}")
    rc, o = remote.run(f'docker exec {PG} psql -U postgres -d thingsboard -tAc "{sql}"', timeout=600, c=c)
    try:
        return int(o.strip().splitlines()[-1])
    except Exception:
        return None


def cassandra_rows(start_ms, end_ms, devices, c):
    """Cassandra 的 ts_kv_cf 必须按分区键查：逐台设备一条 COUNT，写成一个 CQL 文件在 cqlsh 中一次执行后求和。
    分区按月（ThingsBoard 默认 TS_KV_PARTITIONING=MONTHS），时间窗跨月时两个分区都查。"""
    import calendar
    parts = set()
    for ms in (start_ms, end_ms):
        t = time.gmtime(ms / 1000)
        parts.add(calendar.timegm((t.tm_year, t.tm_mon, 1, 0, 0, 0)) * 1000)
    lines = [f"SELECT COUNT(*) FROM thingsboard.ts_kv_cf WHERE entity_type = 'DEVICE' AND entity_id = {d['id']} "
             f"AND key = 'Ua' AND partition = {p} AND ts >= {int(start_ms)} AND ts < {int(end_ms)};"
             for d in devices for p in sorted(parts)]
    local = HERE / '.tb-count.cql'
    local.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    try:
        remote.put(str(local), '/tmp/tb-count.cql', c=c)
    finally:
        local.unlink(missing_ok=True)
    rc, o = remote.run('docker cp /tmp/tb-count.cql tbk-cassandra:/tmp/q.cql >/dev/null && '
                       "docker exec tbk-cassandra cqlsh --request-timeout=60 -f /tmp/q.cql | "
                       "awk '/^ *[0-9]+ *$/{s+=$1; n++} END{print n, s}'", timeout=1800, c=c)
    try:
        n, total = o.strip().split()[-2:]
        return int(total) if int(n) == len(lines) else None
    except Exception:
        return None


def kafka_lag(c):
    """Kafka 全部消费组的 lag 之和（ThingsBoard 的核心与规则引擎队列）；查询失败返回 None。"""
    rc, o = remote.run("docker exec tbk-kafka /opt/bitnami/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 "
                       "--describe --all-groups 2>/dev/null | awk '$6 ~ /^[0-9]+$/ {s+=$6} END{print s+0}'", timeout=300, c=c)
    try:
        return int(o.strip().split()[-1])
    except Exception:
        return None


def cpu_jiffies(c):
    """应用主机 /proc/stat 第一行：(总 jiffies, 空闲 jiffies)。"""
    f = [int(x) for x in remote.run('head -1 /proc/stat', c=c)[1].split()[1:]]
    return sum(f), f[3] + f[4]


class Sampler(threading.Thread):
    """每 30 秒采一次 tb-node / tb-postgres 的 docker stats 与应用主机 /proc/stat；
    结束时给出各容器 CPU 平均与内存峰值，以及稳态窗口内的整机 CPU（与 ladder.py 只看稳态的口径一致）。"""

    def __init__(self):
        super().__init__(daemon=True)
        self.stop = threading.Event()
        self.rows = []
        self.jiffies = []   # (时刻, 总 jiffies, 空闲 jiffies)

    def run(self):
        c = remote.connect()
        try:
            while True:
                self.jiffies.append((time.time(), *cpu_jiffies(c)))
                if self.stop.wait(30):
                    break
                o = remote.run("docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' " + ' '.join(CONTAINERS),
                               c=c)[1]
                for line in o.splitlines():
                    p = line.split()
                    if len(p) >= 3 and p[1].endswith('%'):
                        mem = p[2]
                        mib = float(mem[:-3]) * (1024 if mem.endswith('GiB') else 1) if mem[-3:] in ('GiB', 'MiB') else None
                        self.rows.append((p[0], float(p[1][:-1]), mib))
        finally:
            c.close()

    def busy_between(self, t0, t1):
        """[t0, t1] 内首尾两次采样之间的整机 CPU 忙碌百分比；采样不足返回 None。"""
        js = [j for j in self.jiffies if t0 <= j[0] <= t1]
        if len(js) < 2:
            return None
        return round(100 * (1 - (js[-1][2] - js[0][2]) / max(1, js[-1][1] - js[0][1])), 1)

    def summary(self):
        out = {}
        for name in CONTAINERS:
            r = [x for x in self.rows if x[0] == name]
            if r:
                out[name] = {'cpu_avg': round(sum(x[1] for x in r) / len(r), 1),
                             'mem_max_mib': round(max(x[2] or 0 for x in r))}
        return out


def run_step(a, extra, total, rate, out_dir):
    """跑一档并核对，返回判定 dict。"""
    env = {**os.environ, 'PYTHONIOENCODING': 'utf-8', 'LOADGEN_DEVICES': str(TB_DEVICES),
           'LOADGEN_MQTT_HOST': tb_host(), 'LOADGEN_MQTT_PORT': '1883'}
    if a.rate_plan:
        args = ['--devices', str(total), '--rate-plan', f'{rate:g}']
    else:
        args = ['--plan', str(total)] + ([] if a.mix else ['--rate', f'{rate:g}'])
    c = remote.connect()
    try:
        j0 = cpu_jiffies(c)
        t_cpu0 = time.time()
        smp = Sampler()
        smp.start()
        p = subprocess.run([sys.executable, 'ladder.py', 'run', *args, '--hold', str(a.hold), '--no-check',
                            '--out', str(out_dir), *extra], cwd=HERE, env=env, capture_output=True, text=True,
                           encoding='utf-8', errors='replace')
        smp.stop.set()
        j1 = cpu_jiffies(c)
        files = sorted(pathlib.Path(out_dir).glob('*-shard1of1-*.json'), key=lambda f: f.stat().st_mtime)
        if p.returncode or not files:
            raise SystemExit(p.stdout[-3000:] + p.stderr[-3000:])
        r = json.loads(files[-1].read_text(encoding='utf-8'))
        w0, w1 = (r['t_start'] - 5) * 1000, (r['t_end'] + 5) * 1000
        devs = json.loads(TB_DEVICES.read_text(encoding='utf-8'))[:r['devices']]
        time.sleep(max(0, r['t_load_end'] + 5 - time.time()))
        t_drain0 = time.time()
        if KC:
            # 积压：发压结束 5 秒后 Kafka 各消费组的 lag 之和；排空：lag 归零（最多 15 分钟），再等 15 秒让写入落盘后计数
            backlog = kafka_lag(c)
            lag = backlog
            while lag and time.time() - t_drain0 < 900:
                time.sleep(10)
                lag = kafka_lag(c)
            drain_s = time.time() - t_drain0 if backlog else 0.0
            time.sleep(15)
            last = rows_end = ts_rows(w0, w1, c, devs)
        else:
            # 积压：发压结束 5 秒后还没入库的条数；排空：每 10 秒查一次，连续两次不变即视为排空（最多 15 分钟）
            rows_end = ts_rows(w0, w1, c)
            backlog = None if rows_end is None else r['acked'] - rows_end
            last, same = rows_end, 0
            while same < 2 and time.time() - t_drain0 < 900:
                time.sleep(10)
                now = ts_rows(w0, w1, c)
                same = same + 1 if now == last else 0
                last = now
            drain_s = max(0.0, time.time() - t_drain0 - 20) if last != rows_end else 0.0
        errors = remote.run("docker logs --since {} " + NODE + " 2>&1 | grep -c ' ERROR '".format(
            time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(r['t_start']))), c=c)[1].strip().split()[-1]
    finally:
        c.close()
    busy = smp.busy_between(r['t_load_start'], r['t_load_end'])
    if busy is None:
        busy = round(100 * (1 - (j1[1] - j0[1]) / max(1, j1[0] - j0[0])), 1)
    flow = r['devices'] * r['rate_per_device']
    v = {'devices': r['devices'], 'rate': round(flow), 'acked': r['acked'], 'rows': last, 'rows_at_end': rows_end,
         'backlog_end': backlog, 'drain_s': round(drain_s),
         'p50_ms': r['p50_ms'], 'p99_ms': r['p99_ms'], 'disconnects': r['disconnects'],
         'rejected': len(r['rejected']), 'app_cpu': busy, 'tb_errors': int(errors) if errors.isdigit() else None,
         'gen_proc_cpu_max': r.get('gen_proc_cpu_max'), 'host_cpu_avg': r.get('host_cpu_avg'),
         'containers': smp.summary(), 'cpu_window_s': round(time.time() - t_cpu0)}
    reasons = []
    if v['rejected']:
        reasons.append(f'{v["rejected"]} 台鉴权被拒')
    if v['rows'] != v['acked']:
        reasons.append(f'守恒不等：PUBACK {v["acked"]:,} ≠ ts_kv {v["rows"]}')
    if v['disconnects']:
        reasons.append(f'{v["disconnects"]} 台运行中掉线')
    if (v['p99_ms'] or 0) > 1000:
        reasons.append(f'p99 {v["p99_ms"]:.0f} ms > 1000')
    if (v['backlog_end'] or 0) > flow * 5:
        reasons.append(f'积压 {v["backlog_end"]:,} 超过 5 秒流量 {flow * 5:,.0f}（排空 {v["drain_s"]} 秒）')
    if (v['gen_proc_cpu_max'] or 0) > GEN_PROC_LIMIT:
        reasons.append(f'结果不可信：最忙发压进程 {v["gen_proc_cpu_max"]:.0f}% 单核')
    if (v['host_cpu_avg'] or 0) > HOST_CPU_LIMIT:
        reasons.append(f'结果不可信：宿主机 CPU 平均 {v["host_cpu_avg"]:.0f}%')
    v['ok'], v['reasons'] = not reasons, reasons
    (pathlib.Path(out_dir) / f'verdict-{round(flow):06d}.json').write_text(json.dumps(v, ensure_ascii=False, indent=1),
                                                                         encoding='utf-8')
    return v


def cmd_run(a, extra):
    if a.rate_plan:
        steps = [(a.devices, float(x)) for x in a.rate_plan.split(',')]
    else:
        steps = [(int(x), a.rate) for x in a.plan.split(',')]
    out = pathlib.Path(a.out or HERE / 'results' / ('tb-' + time.strftime('%Y%m%d-%H%M%S')))
    out.mkdir(parents=True, exist_ok=True)
    print('| 设备数 | 消息/秒 | 结论 | PUBACK = ts_kv 行 | p50 / p99 ms | 结束时积压 | 排空秒 | 掉线 | 应用主机 CPU% | '
          + ' | '.join(f'{n} CPU% / 内存 MiB' for n in CONTAINERS) + ' | tb ERROR 行 | 不合格原因 |', flush=True)
    for total, rate in steps:
        v = run_step(a, extra, total, rate, out)
        cs = v['containers']
        f = lambda n: f'{cs[n]["cpu_avg"]} / {cs[n]["mem_max_mib"]}' if n in cs else '-'
        print(f'| {v["devices"]:,} | {v["rate"]:,} | {"✅" if v["ok"] else "❌"} | {v["acked"]:,} / {v["rows"]} | '
              f'{v["p50_ms"]} / {v["p99_ms"]} | {v["backlog_end"]} | {v["drain_s"]} | {v["disconnects"]} | {v["app_cpu"]} | '
              + ' | '.join(f(n) for n in CONTAINERS) + f' | {v["tb_errors"]} | {"；".join(v["reasons"]) or "-"} |', flush=True)
        if not v['ok']:
            break
        time.sleep(a.gap)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    pv = sub.add_parser('provision')
    pv.add_argument('--devices', type=int, required=True)
    r = sub.add_parser('run')
    r.add_argument('--plan', default='1000')
    r.add_argument('--rate', type=float, default=1.0)
    r.add_argument('--devices', type=int, default=1000)
    r.add_argument('--rate-plan')
    r.add_argument('--hold', type=int, default=300)
    r.add_argument('--gap', type=int, default=60)
    r.add_argument('--mix')
    r.add_argument('--out')
    a, extra = ap.parse_known_args()
    if a.cmd == 'provision':
        provision(a.devices)
    else:
        if a.mix:
            extra += ['--mix', a.mix]
        cmd_run(a, extra)


if __name__ == '__main__':
    main()
