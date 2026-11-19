"""Laboratory-only bridge/host comparison. Restores bridge and paused containers.

Run from the repository root; credentials come exclusively from ignored config.json.
Each mode/level/repeat gets an independent ladder result directory.
"""
import argparse
import base64
import ctypes
import hashlib
import json
import os
import pathlib
import shlex
import subprocess
import sys
import time

import remote

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OTHER = ('influxdb_v1', 'influxdb_v2', 'influxdb_v3', 'minio', 'postgres',
         'rabbitmq', 'redis', 'xxl-job')


def idle_seconds():
    """Windows input inactivity; unavailable observations must not allow a load run."""
    if os.name != 'nt':
        raise RuntimeError('Reliable input idle observation requires Windows')

    class LastInput(ctypes.Structure):
        _fields_ = [('size', ctypes.c_uint32), ('tick', ctypes.c_uint32)]

    info = LastInput()
    info.size = ctypes.sizeof(info)
    if not ctypes.windll.user32.GetLastInputInfo(ctypes.byref(info)):
        raise RuntimeError('Cannot observe Windows input idle state')
    return ((ctypes.windll.kernel32.GetTickCount() - info.tick) & 0xffffffff) / 1000


def cpu_times():
    """Return system idle and total CPU ticks, without starting sampler processes."""
    idle, kernel, user = (ctypes.c_uint64() for _ in range(3))
    if not ctypes.windll.kernel32.GetSystemTimes(ctypes.byref(idle), ctypes.byref(kernel), ctypes.byref(user)):
        raise RuntimeError('Cannot observe Windows CPU state')
    return idle.value, kernel.value + user.value


def require_idle():
    if idle_seconds() < 600:
        raise RuntimeError('Computer is in use; waiting for ten minutes without input')
    previous = cpu_times()
    samples = []
    for _ in range(12):
        time.sleep(5)
        if idle_seconds() < 600:
            raise RuntimeError('User resumed input during idle preflight')
        current = cpu_times()
        total = current[1] - previous[1]
        if total <= 0:
            raise RuntimeError('CPU sample unavailable')
        samples.append(100 * (1 - (current[0] - previous[0]) / total))
        previous = current
    if sum(samples) / len(samples) >= 15 or max(samples) >= 30:
        raise RuntimeError('CPU is not idle enough to start a load run')
    print(f'Idle preflight passed: CPU avg={sum(samples)/len(samples):.1f}%, peak={max(samples):.1f}%', flush=True)


def unrelated_cpu_percent(rows, parents, owned_pid, cores):
    """Exclude this driver's descendants and VM runtime; other Python agents still count."""
    owned = {owned_pid}
    while True:
        children = {pid for pid, parent in parents.items() if parent in owned}
        if children <= owned:
            break
        owned.update(children)
    return sum(float(row['PercentProcessorTime']) for row in rows
               if int(row['IDProcess']) not in owned
               and int(row['IDProcess']) != 0
               and row['Name'] != '_Total'
               and not row['Name'].lower().startswith('vmware-vmx')) / cores


def background_cpu():
    # Formatted counters provide percentages summed across logical cores.
    # Never persist process names/command lines: only return the aggregate percentage.
    script = ("[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new(); "
              "$p=@(Get-CimInstance Win32_Process -ErrorAction Stop | Select-Object ProcessId,ParentProcessId); "
              "$c=@(Get-CimInstance Win32_PerfFormattedData_PerfProc_Process -ErrorAction Stop | "
              "Select-Object IDProcess,Name,PercentProcessorTime); "
              "@{parents=$p;cpu=$c}|ConvertTo-Json -Depth 4 -Compress")
    result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', script],
                            capture_output=True, text=True, encoding='utf-8', timeout=20, check=True)
    data = json.loads(result.stdout.lstrip('\ufeff'))
    if not data.get('cpu') or not data.get('parents'):
        raise RuntimeError('Background CPU observation unavailable')
    parents = {int(row['ProcessId']): int(row['ParentProcessId']) for row in data['parents']}
    return unrelated_cpu_percent(data['cpu'], parents, os.getpid(), os.cpu_count() or 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--hold', type=int, default=300)
    parser.add_argument('--levels', default='5000,6000')
    parser.add_argument('--require-idle', action='store_true', help='Check CPU/input inactivity and stop when input resumes')
    parser.add_argument('--start-mode', choices=('bridge', 'host'), default='bridge', help='Reverse the initial order for a subsequent comparison')
    parser.add_argument('--bridge-only', action='store_true', help='Probe the retained bridge configuration without another host comparison')
    args = parser.parse_args()
    if args.repeats < 1 or args.hold < 60:
        parser.error('repeats >= 1 and hold >= 60 are required')
    levels = [int(x) for x in args.levels.split(',')]
    if any(x < 1 for x in levels):
        parser.error('device counts must be positive')
    if args.require_idle:
        require_idle()
    out = HERE / 'results' / ('network-ab-' + time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir(parents=True)
    c = remote.connect()
    endpoint = remote.config().get('emqx_docker')
    if not endpoint:
        raise RuntimeError('This comparison requires split deployment')
    docker = 'docker -H ' + shlex.quote(endpoint)
    paused = []
    simulator_paused = False
    switched = False

    def run(cmd, timeout=300):
        code, output = remote.run(cmd, timeout=timeout, c=c)
        if code:
            raise RuntimeError(f'Remote command failed ({code}): {output[-1500:]}')
        return output

    def host(cmd):
        return run(docker + ' run --rm --privileged --pid=host --net=host '
                   '--entrypoint nsenter nginx:1.27.4-alpine '
                   '-t 1 -m -u -n -i -- sh -c ' + shlex.quote(cmd))

    def inspect():
        return json.loads(run(docker + ' inspect conduit-emqx'))[0]

    initial = inspect()
    if initial['HostConfig']['NetworkMode'] == 'host':
        raise RuntimeError('Expected bridge as initial state; refusing to guess rollback')
    directory = initial['Config']['Labels']['com.docker.compose.project.working_dir']
    compose = 'cd ' + shlex.quote(directory) + ' && docker compose -f docker-compose.yml'

    def switch(mode):
        nonlocal switched
        files = ' -f docker-compose.host.yml' if mode == 'host' else ''
        switched = True  # Restore even when recreation fails halfway.
        host(compose + files + ' up -d --no-deps emqx')
        expected = 'host' if mode == 'host' else initial['HostConfig']['NetworkMode']
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            current = inspect()
            if current['State'].get('Health', {}).get('Status') == 'healthy':
                if current['HostConfig']['NetworkMode'] != expected:
                    raise RuntimeError('Network mode readback mismatch')
                print(f'Network readback: {mode}, healthy', flush=True)
                return
            time.sleep(3)
        raise RuntimeError('EMQX health did not recover in 120 seconds')

    def snapshot(name):
        text = host("date -u; grep '^cpu' /proc/stat; cat /proc/softirqs; "
                    "cat /proc/net/softnet_stat; ethtool -S ens33; "
                    "sysctl net.core.somaxconn net.ipv4.tcp_max_syn_backlog; "
                    "ss -lnt; free -m")
        (out / (name + '.txt')).write_text(text, encoding='utf-8')

    try:
        # Upload only the non-secret overlay; the host's existing base and .env remain in place.
        content = (ROOT / 'deploy/split/emqx/docker-compose.host.yml').read_bytes()
        data = base64.b64encode(content).decode()
        digest = hashlib.sha256(content).hexdigest()
        path = shlex.quote(directory + '/docker-compose.host.yml')
        host('if test -e ' + path + '; then '
             'test "$(sha256sum ' + path + ' | cut -d\" \" -f1)" = ' + shlex.quote(digest)
             + ' || { echo "Existing overlay differs; refusing to replace"; exit 17; }; '
             'else printf %s ' + shlex.quote(data) + ' | base64 -d > ' + path + '; fi')
        host(compose + ' -f docker-compose.host.yml config --quiet')
        names = set(run(docker + ' ps --format ' + shlex.quote('{{.Names}}')).splitlines())
        for name in OTHER:
            if name in names:
                run(docker + ' stop ' + name)
                paused.append(name)
        if 'conduit-simulator' in run('docker ps --format ' + shlex.quote('{{.Names}}')).splitlines():
            run('docker stop conduit-simulator')
            simulator_paused = True
        # Do not interrupt a pre-existing load run: wait until transport queues and Redis drain.
        for _ in range(20):
            lag = remote.prom('sum(redis_stream_group_lag) + sum(redis_stream_group_messages_pending)', c)
            mq = remote.prom('sum(conduit_transport_session_mqueue_len >= 0)', c)
            if lag is not None and lag == 0 and mq is not None and mq == 0:
                break
            time.sleep(3)
        else:
            raise RuntimeError('Cannot establish an empty baseline')
        for repeat in range(1, args.repeats + 1):
            modes = ('bridge', 'host') if repeat % 2 else ('host', 'bridge')
            if args.start_mode == 'host':
                modes = tuple(reversed(modes))
            if args.bridge_only:
                modes = ('bridge',)
            for mode in modes:
                if args.require_idle and idle_seconds() < 600:
                    raise RuntimeError('User resumed input; comparison paused')
                if (inspect()['HostConfig']['NetworkMode'] == 'host') != (mode == 'host'):
                    switch(mode)
                    time.sleep(30)
                for devices in levels:
                    if args.require_idle and idle_seconds() < 600:
                        raise RuntimeError('User resumed input; comparison paused')
                    label = f'r{repeat}-{mode}-{devices}'
                    print(f'START {label}', flush=True)
                    snapshot(label + '-before')
                    log_since = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(time.time() - 5))
                    result_dir = out / label
                    env = {**os.environ, 'PYTHONUTF8': '1', 'PYTHONIOENCODING': 'utf-8'}
                    process = subprocess.Popen([sys.executable, '-X', 'utf8', str(HERE / 'ladder.py'), 'run',
                                             '--plan', str(devices), '--rate', '1',
                                             '--hold', str(args.hold), '--out', str(result_dir)], env=env)
                    try:
                        next_background_check = 0
                        high_background_samples = 0
                        while process.poll() is None:
                            if args.require_idle and idle_seconds() < 600:
                                (out / (label + '-aborted.txt')).write_text('User resumed input; load interrupted, not a completed capacity result.', encoding='utf-8')
                                raise RuntimeError('User resumed input; comparison paused')
                            if args.require_idle and time.monotonic() >= next_background_check:
                                percent = background_cpu()
                                with (out / (label + '-background.jsonl')).open('a', encoding='utf-8') as stream:
                                    stream.write(json.dumps({'at': time.time(), 'unrelated_cpu_percent': percent}) + '\n')
                                high_background_samples = high_background_samples + 1 if percent >= 15 else 0
                                if high_background_samples >= 2:
                                    (out / (label + '-aborted.txt')).write_text('Background CPU exceeded 15% for two consecutive observations; not a completed capacity result.', encoding='utf-8')
                                    raise RuntimeError('Background CPU increased; comparison paused')
                                next_background_check = time.monotonic() + 15
                            time.sleep(2)
                    finally:
                        if process.poll() is None:
                            # Kill only this specifically launched load process and its descendants.
                            subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
                            process.wait(timeout=30)
                    snapshot(label + '-after')
                    # Keep the relevant events BEFORE recreating EMQX for the next mode.
                    # Do not capture authentication/request payload logs.
                    events = run(docker + ' logs --since ' + shlex.quote(log_since)
                                 + ' conduit-emqx 2>&1 | grep -E '
                                 + shlex.quote('alarm_is_activated|check_oom_shutdown|mailbox_overflow|mqueue_is_full|maximum heap size|emfile')
                                 + ' || test "$?" -eq 1')
                    (out / (label + '-events.log')).write_text(events, encoding='utf-8')
                    if process.returncode:
                        raise RuntimeError(f'Load process failed: {label}')
                    # ladder writes truthful failed verdicts but exits zero; preserve them for comparison.
                    print(f'FINISH {label}; results: {result_dir.name}', flush=True)
        print('All planned comparisons completed.', flush=True)
    finally:
        failures = []
        if switched:
            try:
                switch('bridge')
            except Exception as e:
                failures.append(f'bridge restore failed: {e}')
        for name in paused:
            try:
                run(docker + ' start ' + name)
            except Exception as e:
                failures.append(f'{name} restore failed: {e}')
        if simulator_paused:
            try:
                run('docker start conduit-simulator')
            except Exception as e:
                failures.append(f'simulator restore failed: {e}')
        c.close()
        print('RESTORE: ' + ('; '.join(failures) if failures else 'bridge and original services restored'), flush=True)
        if failures:
            raise RuntimeError('Manual recovery required; see restore output')


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
    main()
