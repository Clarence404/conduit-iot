"""容量测试：找「积压不持续增长」的最大稳态速率。

与压力测试的区别：判据不是吞吐数字，而是两个队列涨不涨——
  EMQX mqueue_len        入口有没有堆
  Redis 消费组 lag       出口有没有堆
两个都平 → 该速率在容量以内；任一单调上涨 → 超了。

两条必须遵守的方法纪律（都是踩过坑换来的）：

1. **速率由 Prometheus 的 rate() 计算，不要在外面对计数器做差分。**
   抓取间隔 15 秒 + SSH 往返耗时不固定，自行差分噪声极大——实测同一档位
   单点值从 1,570 跳到 3,799，中位数被压到 2,400，据此一度误判结论有问题。

2. **判「容量以内」之前，先确认实际喂进去的量达到了目标。**
   若负载端没打满，队列当然不涨，但那不叫扛得住。本脚本要求接入 QPS 中位
   达到目标的 85%，否则标注「结论不可用」。

用法：
    python capacity_test.py --levels 3000 4000 5000 6000 --devices 600 --seconds 180
"""
import argparse
import statistics
import subprocess
import sys
import threading
import time
import pathlib

import remote

HERE = pathlib.Path(__file__).parent
REACH_RATIO = 0.85          # 接入中位达到目标的多少才算打满
GROW_FACTOR = 1.5           # 末三均值高于首三均值多少倍算单调上涨
GROW_ABSOLUTE = 5000        # 且绝对增量要超过多少条，避免小数字的比例噪声

Q_QPS = 'rate(emqx_messages_received[1m])'
Q_TPS = 'sum(rate(conduit_pipeline_processed_total{type="property"}[1m]))'
Q_MQ = 'conduit_transport_session_mqueue_len'
Q_LAG = 'sum(redis_stream_group_lag)'


def grows(xs):
    """积压是否单调上涨。"""
    if len(xs) < 6:
        return False
    a, b = statistics.mean(xs[:3]), statistics.mean(xs[-3:])
    return b > a * GROW_FACTOR and (b - a) > GROW_ABSOLUTE


def load(devices, rate, seconds):
    """以子进程方式调用多进程发压器，避免与本脚本争 GIL。"""
    subprocess.run([sys.executable, str(HERE / 'loadgen.py'), 'run',
                    '--devices', str(devices), '--rate', f'{rate}',
                    '--seconds', str(seconds)], cwd=str(HERE))


def one_level(c, target, devices, seconds):
    per = target / devices
    print(f'\n===== 目标 {target:,} 条/秒（{devices} 台 × {per:.2f}/秒），加压 {seconds} 秒 =====', flush=True)
    th = threading.Thread(target=load, args=(devices, per, seconds), daemon=True)
    th.start()
    qps, tps, lags, mqs = [], [], [], []
    t0 = time.time()
    print(f'{"t":>5} {"接入QPS":>9} {"入库TPS":>9} {"mqueue":>8} {"lag":>9}')
    while time.time() - t0 < seconds:
        time.sleep(20)
        vals = [remote.prom(q, c=c) for q in (Q_QPS, Q_TPS, Q_MQ, Q_LAG)]
        for v, arr in zip(vals, (qps, tps, mqs, lags)):
            if v is not None:
                arr.append(v)
        f = lambda v: f'{v:,.0f}' if v is not None else '-'
        print(f'{time.time()-t0:5.0f} {f(vals[0]):>9} {f(vals[1]):>9} {f(vals[2]):>8} {f(vals[3]):>9}', flush=True)
    th.join(timeout=seconds + 300)
    # 丢弃前两个采样：rate(...[1m]) 的窗口此时尚未填满
    qs, ts = qps[2:] or qps, tps[2:] or tps
    qmed = statistics.median(qs) if qs else 0
    reached = qmed >= target * REACH_RATIO
    over = grows(lags) or grows(mqs)
    print(f'  >> 接入中位 {qmed:,.0f}（目标 {target:,}，'
          f'{"已打满" if reached else "**未打满，负载端可能是瓶颈**"}）'
          f'  入库中位 {statistics.median(ts) if ts else 0:,.0f}  '
          f'积压{"持续增长" if over else "平稳"}', flush=True)
    return dict(target=target, qps_med=qmed, qps_max=max(qs or [0]),
                tps_med=statistics.median(ts) if ts else 0, tps_max=max(ts or [0]),
                reached=reached, over=over)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--levels', type=int, nargs='+', required=True, help='逐级目标速率（条/秒）')
    ap.add_argument('--devices', type=int, default=600)
    ap.add_argument('--seconds', type=int, default=180)
    ap.add_argument('--keep-simulator', action='store_true', help='不停演示模拟器（默认停掉以取干净基线）')
    a = ap.parse_args()

    c = remote.connect()
    try:
        if not a.keep_simulator:
            remote.run("docker stop conduit-simulator-1 >/dev/null 2>&1", timeout=300, c=c)
            time.sleep(10)
        rows = [one_level(c, t, a.devices, a.seconds) for t in a.levels]

        print('\n===== 汇总 =====')
        print(f'{"目标":>8} {"接入中位":>9} {"接入峰值":>9} {"入库中位":>9} {"入库峰值":>9}  判定')
        cap = None
        for r in rows:
            v = ('负载端未打满（结论不可用）' if not r['reached']
                 else '超容量' if r['over'] else '容量以内')
            print(f'{r["target"]:>8,} {r["qps_med"]:>9,.0f} {r["qps_max"]:>9,.0f} '
                  f'{r["tps_med"]:>9,.0f} {r["tps_max"]:>9,.0f}  {v}')
            if r['reached'] and not r['over']:
                cap = r
        print(f'\n可持续稳态容量：{cap["target"]:,} 条/秒' if cap
              else '\n未得出容量：所有档位要么超容量、要么负载端没打满')
        rc, o = remote.run("docker logs --since 20m conduit-emqx-1 2>&1 | "
                           "grep -icE 'check_oom_shutdown|maximum heap size|mqueue_is_full'", timeout=300, c=c)
        print(f'期间 EMQX 失效事件：{o.strip()} 次'
              f'{"（压力测试必须核对此项）" if o.strip() != "0" else ""}')
    finally:
        if not a.keep_simulator:
            remote.run("docker start conduit-simulator-1 >/dev/null 2>&1", timeout=300, c=c)
        c.close()


if __name__ == '__main__':
    main()
