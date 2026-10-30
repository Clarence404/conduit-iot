"""阶梯压测：逐档加设备，每档稳态保持一段时间，按守恒与稳定性判据判定合格，支持多台发压机分担。

  run   [--plan 2000,5000,...] [--rate 1] [--hold 300] [--shard i/n] [--start-at HH:MM] [--no-check]
        逐档发压，每档结果写入 --out 目录（默认 results/<开始时间>/）。
        单机（不给 --shard）时每档结束后自动判定，首个不合格的档位即停。
        多机时各发压机用同一组 --plan / --rate / --hold / --start-at、各给不同的 --shard，
        按约定时刻同步换档（依赖各机时钟准确），只发压、不判定。
  check 目录 [目录 ...]
        汇总一台或多台发压机的结果目录，按档判定并输出表格与 summary.md。需能 SSH 到应用主机（config.json 的 ssh）。

判据（任一不满足即不合格）：
  1. 全部设备通过鉴权（首次被拒的设备会重连一次，重连后仍被拒才算）；
  2. 守恒：各发压机收到 PUBACK 的条数之和 = InfluxDB 中压测产品在本档时间窗的实际行数，EMQX 丢弃与死信均为 0；
  3. 运行中没有设备掉线；
  4. PUBACK 时延 p99 ≤ --p99-limit（默认 1000 毫秒，多机时取各机 p99 的最大值）；
  5. 积压不堆积：稳态结束时总线积压（lag + 待确认）与 transport 会话队列均不超过 5 秒的流量；
  6. EMQX 日志中没有连接被强杀 / 队列满 / emfile，也没有过载告警（alarm_is_activated）。

依赖：pip install paho-mqtt paramiko（paramiko 仅 check 需要）。
设备与凭据取自同目录的 devices.json 与 config.json（均不入库）；多机时把这两个文件拷给各发压机，
devices.json 至少要有 n × ceil(最大档位 / n) 台（loadgen.py provision 补齐）。
"""
import argparse
import json
import math
import pathlib
import socket
import sys
import time

import loadgen

HERE = pathlib.Path(__file__).parent
DEFAULT_PLAN = '2000,5000,8000,10000,12000,15000,18000,20000'
DRAIN = 30       # 停止发压后等多久再核对（排空流水线与最后一次指标抓取）
UP = '{stream="conduit:stream:up"}'


def parse_shard(s):
    """'2/3' → (2, 3)；不给时为单机 (1, 1)。"""
    if not s:
        return 1, 1
    i, n = (int(x) for x in s.split('/'))
    if not 1 <= i <= n:
        raise SystemExit(f'--shard {s} 不合法：应为 i/n 且 1 <= i <= n')
    return i, n


def share_of(total, i, n):
    """本机在 total 台设备中分到的台数：均分，余数给前几台。"""
    return total // n + (1 if i <= total % n else 0)


def start_time(s):
    """--start-at：HH:MM[:SS]（今天，已过则明天）或 epoch 秒；不给时 10 秒后开始。"""
    if not s:
        return time.time() + 10
    if s.replace('.', '').isdigit():
        return float(s)
    parts = [int(x) for x in s.split(':')] + [0]
    lt = time.localtime()
    t = time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday, parts[0], parts[1], parts[2], 0, 0, -1))
    return t if t > time.time() else t + 86400


def cmd_run(a):
    plan = [int(x) for x in a.plan.split(',')]
    i, n = parse_shard(a.shard)
    block = math.ceil(max(plan) / n)      # 每台发压机独占 devices.json 中的一段，互不重叠
    total_devices = len(json.loads(loadgen.DEVICES_FILE.read_text(encoding='utf-8')))
    if total_devices < n * block:
        raise SystemExit(f'devices.json 只有 {total_devices} 台，{n} 台发压机分担 {max(plan)} 台需要 {n * block} 台；'
                         f'先 python loadgen.py provision --devices {n * block}')
    out = pathlib.Path(a.out or HERE / 'results' / time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir(parents=True, exist_ok=True)
    host = socket.gethostname()
    slot = a.connect_budget + a.hold + a.gap
    t0 = start_time(a.start_at)
    print(f'阶梯 {plan}，每台每秒 {a.rate} 条，每档稳态 {a.hold} 秒；本机 {host} 分片 {i}/{n}，结果写入 {out}')
    if n > 1:
        print(f'多机模式：第 k 档于 {time.strftime("%H:%M:%S", time.localtime(t0))} + k × {slot} 秒开始，各机须用同一组参数')
    for k, total in enumerate(plan):
        begin = t0 + k * slot if n > 1 else (t0 if k == 0 else time.time())
        wait = begin - time.time()
        if wait > 0:
            print(f'\n等待 {wait:.0f} 秒到 {time.strftime("%H:%M:%S", time.localtime(begin))} 开始 {total:,} 台档')
            time.sleep(wait)
        elif n > 1 and wait < -5:
            print(f'**警告：{total:,} 台档比约定时刻晚了 {-wait:.0f} 秒开始，与其他发压机不同步**')
        mine = share_of(total, i, n)
        offset = (i - 1) * block
        print(f'\n===== {total:,} 台（本机 {mine:,} 台，devices.json 第 {offset} 台起）=====')
        r = loadgen.execute(mine, a.rate, a.hold, fleet=True, connect_rate=a.connect_rate,
                            connect_timeout=120, offset=offset, per_process=a.per_process, quiet=True)
        r.update({'step_total': total, 'shard': f'{i}/{n}', 'host': host, 'rate_per_device': a.rate, 'hold': a.hold})
        f = out / f'{host}-shard{i}of{n}-{total:06d}.json'
        f.write_text(json.dumps(r, ensure_ascii=False, indent=1), encoding='utf-8')
        print(f'  发出 {r["sent"]:,}，PUBACK {r["acked"]:,}，掉线 {r["disconnects"]}，首次被拒 {r["first_rejected"]}，'
              f'最终被拒 {len(r["rejected"])}，p99 {r["p99_ms"]} ms → {f.name}')
        if n == 1 and not a.no_check:
            time.sleep(DRAIN)
            verdicts = check([out], only=total, p99_limit=a.p99_limit)
            if not verdicts or not verdicts[0]['ok']:
                print(f'\n**{total:,} 台档不合格，阶梯停止。**')
                break
            time.sleep(max(0, a.gap - DRAIN))
    print(f'\n结果目录：{out}')


def load_results(dirs):
    """读取各目录下的结果 JSON，按档位分组。"""
    steps = {}
    for d in dirs:
        for f in sorted(pathlib.Path(d).glob('*-shard*of*-*.json')):
            r = json.loads(f.read_text(encoding='utf-8'))
            steps.setdefault(r['step_total'], []).append(r)
    return steps


def check(dirs, only=None, p99_limit=1000.0):
    """按档汇总并判定；返回每档的判定 dict 列表（按档位升序），同时打印表格并写 summary.md 到第一个目录。"""
    import remote
    steps = load_results(dirs)
    pk = json.loads(loadgen.DEVICES_FILE.read_text(encoding='utf-8'))[0]['productKey']
    c = remote.connect()
    verdicts = []
    try:
        for total in sorted(steps):
            if only is not None and total != only:
                continue
            rs = steps[total]
            t_start = min(r['t_start'] for r in rs)
            t_end = max(r['t_end'] for r in rs)
            load_s = max(r['t_load_start'] for r in rs)
            load_e = max(r['t_load_end'] for r in rs)
            hold = max(1, int(load_e - load_s))
            w = max(hold, 60)   # 速率类窗口至少 60 秒：短于抓取间隔 15 秒的几倍时 rate()/increase() 会取不到值
            rate = total * rs[0]['rate_per_device']
            acked = sum(r['acked'] for r in rs)
            v = {
                'devices': total, 'machines': len(rs), 'rate': rate,
                'sent': sum(r['sent'] for r in rs), 'acked': acked, 'errors': sum(r['errors'] for r in rs),
                'rejected': sum(len(r['rejected']) for r in rs), 'first_rejected': sum(r.get('first_rejected', 0) for r in rs),
                'disconnects': sum(r['disconnects'] for r in rs),
                'p99_ms': max((r['p99_ms'] or 0) for r in rs), 'max_ms': max((r['max_ms'] or 0) for r in rs),
            }
            # 计数器在空闲时刻取差值：负载中各组件抓取时刻不一致，差值会整体偏移
            after = t_end + DRAIN
            delta = lambda e: (remote.prom(e, c, at=after) or 0) - (remote.prom(e, c, at=t_start - 5) or 0)
            v['rows'] = remote.influx_rows(pk, int((t_start - 5) * 1000), int((t_end + DRAIN) * 1000), c)
            v['dropped'] = delta('sum(emqx_messages_dropped) + sum(emqx_delivery_dropped)')
            v['dead'] = delta('sum(conduit_pipeline_dead_total) or on() vector(0)')
            v['ingest_tps'] = (remote.prom('sum(increase(conduit_pipeline_processed_total{type="property"}[%ds]))' % w,
                                           c, at=load_e) or 0) / w
            v['backlog_end'] = remote.prom(f'sum(redis_stream_group_lag{UP}) + sum(redis_stream_group_messages_pending{UP})',
                                           c, at=load_e)
            v['backlog_max'] = remote.prom(f'max_over_time((sum(redis_stream_group_lag{UP}) + '
                                           f'sum(redis_stream_group_messages_pending{UP}))[{hold}s:15s])', c, at=load_e)
            v['mqueue_end'] = remote.prom('sum(conduit_transport_session_mqueue_len >= 0)', c, at=load_e)
            v['app_cpu'] = remote.prom('100 * (1 - avg(rate(node_cpu_seconds_total{mode="idle"}[%ds])))' % w, c, at=load_e)
            v['emqx_host_cpu'] = remote.prom('avg_over_time(emqx_vm_cpu_use[%ds])' % w, c, at=load_e)
            v['emqx_kills'] = remote.emqx_log_count('check_oom_shutdown|maximum heap size|mqueue_is_full|mailbox_overflow|emfile',
                                                    t_start, c, until=after)
            v['emqx_alarms'] = remote.emqx_log_count('alarm_is_activated', t_start, c, until=after)
            v['auth_req_failed'] = remote.emqx_log_count('http_connector_do_request_failed', t_start, c, until=after)
            limit = rate * 5
            reasons = []
            if v['rejected']:
                reasons.append(f'{v["rejected"]} 台鉴权被拒')
            if v['rows'] is None or v['rows'] != acked:
                reasons.append(f'守恒不等：PUBACK {acked:,} ≠ InfluxDB {v["rows"]}')
            if v['dropped'] or v['dead']:
                reasons.append(f'丢弃 {v["dropped"]:.0f} / 死信 {v["dead"]:.0f}')
            if v['disconnects']:
                reasons.append(f'{v["disconnects"]} 台运行中掉线')
            if v['p99_ms'] > p99_limit:
                reasons.append(f'p99 {v["p99_ms"]:.0f} ms > {p99_limit:.0f}')
            if (v['backlog_end'] or 0) > limit or (v['mqueue_end'] or 0) > limit:
                reasons.append(f'积压 总线 {v["backlog_end"] or 0:.0f} / 会话队列 {v["mqueue_end"] or 0:.0f} 超过 5 秒流量 {limit:.0f}')
            if v['emqx_kills'] or v['emqx_alarms']:
                reasons.append(f'EMQX 强杀等 {v["emqx_kills"]} 行 / 过载告警 {v["emqx_alarms"]} 次')
            v['ok'] = not reasons
            v['reasons'] = reasons
            verdicts.append(v)
    finally:
        c.close()
    print_table(verdicts, pathlib.Path(dirs[0]) / 'summary.md', append=only is not None)
    return verdicts


def print_table(verdicts, md_path, append=False):
    """打印并写入 Markdown 表格。"""
    head = ('| 设备数 | 消息/秒 | 结论 | PUBACK = InfluxDB 行 | 入库 TPS | 掉线 | 首次被拒 | p99 ms | 积压峰值 | '
            '应用主机 CPU% | EMQX 主机 CPU% | 不合格原因 |\n|---|---|---|---|---|---|---|---|---|---|---|---|')
    fmt = lambda x, d=0: '-' if x is None else f'{x:,.{d}f}'
    lines = [f'| {v["devices"]:,} | {v["rate"]:,.0f} | {"✅" if v["ok"] else "❌"} | {v["acked"]:,} / {fmt(v["rows"])} | '
             f'{fmt(v["ingest_tps"])} | {v["disconnects"]} | {v["first_rejected"]} | {fmt(v["p99_ms"])} | '
             f'{fmt(v["backlog_max"])} | {fmt(v["app_cpu"])} | {fmt(v["emqx_host_cpu"])} | {"；".join(v["reasons"]) or "-"} |'
             for v in verdicts]
    text = '\n'.join(lines)
    print('\n' + head + '\n' + text)
    if append and md_path.exists():
        md_path.write_text(md_path.read_text(encoding='utf-8') + '\n' + text, encoding='utf-8')
    else:
        md_path.write_text(head + '\n' + text, encoding='utf-8')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    r = sub.add_parser('run')
    r.add_argument('--plan', default=DEFAULT_PLAN, help='各档总设备数，逗号分隔（多机时为全部发压机合计）')
    r.add_argument('--rate', type=float, default=1.0, help='每台设备每秒发布条数')
    r.add_argument('--hold', type=int, default=300, help='每档建连后的稳态秒数')
    r.add_argument('--gap', type=int, default=60, help='档与档之间的间隔秒数（排空与判定）')
    r.add_argument('--connect-budget', type=int, default=120, help='多机模式下为建连预留的秒数（决定换档节奏）')
    r.add_argument('--connect-rate', type=float, default=200.0, help='每个发压进程每秒建连数')
    r.add_argument('--per-process', type=int, default=1000, help='每个发压进程负责的设备数')
    r.add_argument('--shard', help='多机分担时本机是第几台/共几台，如 2/3')
    r.add_argument('--start-at', help='多机约定的开始时刻 HH:MM[:SS] 或 epoch 秒')
    r.add_argument('--out', help='结果目录')
    r.add_argument('--no-check', action='store_true', help='单机时也不自动判定')
    r.add_argument('--p99-limit', type=float, default=1000.0, help='p99 时延上限（毫秒）')
    k = sub.add_parser('check')
    k.add_argument('dirs', nargs='+', help='各发压机的结果目录')
    k.add_argument('--p99-limit', type=float, default=1000.0)
    a = ap.parse_args()
    if a.cmd == 'run':
        cmd_run(a)
    else:
        check(a.dirs, p99_limit=a.p99_limit)


if __name__ == '__main__':
    sys.exit(main())
