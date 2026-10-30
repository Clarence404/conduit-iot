"""压测机远程执行助手：SSH 执行命令、上传文件、查 Prometheus。

凭据只从同目录的 config.json 读取（已在 .gitignore 中），绝不硬编码、不打印。

依赖：pip install paramiko
"""
import json
import pathlib
import time
import urllib.parse

import paramiko

HERE = pathlib.Path(__file__).parent


def config():
    """读取同目录的 config.json；缺失时给出可操作的提示而不是堆栈。"""
    f = HERE / 'config.json'
    if not f.exists():
        raise SystemExit(f'缺少 {f}。请复制 config.example.json 为 config.json 并填入真实值。')
    return json.loads(f.read_text(encoding='utf-8'))


def connect(cfg=None):
    """建立到压测机的 SSH 连接。调用方负责 close()。"""
    cfg = cfg or config()
    ssh = cfg['ssh']
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(ssh['host'], port=int(ssh.get('port', 22)),
              username=ssh['username'], password=ssh['password'], timeout=20)
    return c


def run(cmd, timeout=180, c=None):
    """执行一条命令，返回 (退出码, 合并后的 stdout+stderr)。"""
    own = c is None
    c = c or connect()
    try:
        stdin, stdout, stderr = c.exec_command(cmd, timeout=timeout)
        out = stdout.read().decode('utf-8', 'replace')
        err = stderr.read().decode('utf-8', 'replace')
        return stdout.channel.recv_exit_status(), out + err
    finally:
        if own:
            c.close()


def put(local, remote, c=None):
    """上传一个文件。"""
    own = c is None
    c = c or connect()
    try:
        sftp = c.open_sftp()
        sftp.put(str(local), remote)
        sftp.close()
    finally:
        if own:
            c.close()


def prom(expr, c=None, at=None):
    """查 Prometheus 的瞬时值，返回所有非 NaN 结果之和；无数据返回 None。at 为 epoch 秒时查该时刻的值。

    注意用 POST 并对表达式做 URL 编码：表达式里的 `+` 在 form-urlencoded 中
    会被解析成空格，直接拼进 GET 查询串会把表达式拆坏（实测踩过）。
    """
    enc = urllib.parse.quote(expr, safe='')
    if at is not None:
        enc += '&time={:.3f}'.format(at)
    rc, o = run(f"docker exec conduit-prometheus wget -qO- --post-data='query={enc}' "
                f"'http://localhost:9090/api/v1/query' 2>/dev/null", timeout=200, c=c)
    try:
        d = json.loads(o.strip())
        if d.get('status') != 'success':
            return None
        vals = [float(x['value'][1]) for x in d['data']['result'] if x['value'][1] != 'NaN']
        return sum(vals) if vals else None
    except Exception:
        return None


def influx_rows(product_key, start_ms, end_ms, c=None):
    """问 InfluxDB：[start_ms, end_ms) 内该产品实际写进了多少行（守恒判据的「真值」）；查询失败返回 None。

    查询体整体 base64 传过去——SQL 带单引号、JSON 带双引号，直接拼进 shell 必被撑破。
    token 在压测机上就地从 config.json 的 ssh.env_file（应用主机的 .env）读取，不回传、不打印。
    """
    import base64
    env_file = config()['ssh'].get('env_file', '/opt/conduit-loadtest/deploy/split/app/.env')
    iso = lambda ms: time.strftime('%Y-%m-%dT%H:%M:%S', time.gmtime(ms / 1000)) + 'Z'
    sql = ("select count(1) as n from property where product_key = '{}' "
           "and time >= '{}' and time < '{}'").format(product_key, iso(start_ms), iso(end_ms))
    b64 = base64.b64encode(json.dumps({'db': 'telemetry', 'q': sql, 'format': 'json'}).encode()).decode()
    cmd = ('set -a; . {}; set +a; echo {} | base64 -d > /tmp/influx-q.json && '
           'docker cp /tmp/influx-q.json conduit-influxdb:/tmp/q.json >/dev/null && '
           'docker exec conduit-influxdb curl -s -XPOST http://localhost:8181/api/v3/query_sql '
           '-H "Authorization: Bearer $INFLUX_TOKEN" -H "Content-Type: application/json" -d @/tmp/q.json'
           ).format(env_file, b64)
    rc, o = run(cmd, timeout=900, c=c)
    try:
        data = json.loads(o.strip())
        for v in (data[0] if isinstance(data, list) and data else {}).values():
            if isinstance(v, (int, float)):
                return int(v)
    except Exception:
        pass
    return None


def emqx_log_count(pattern, since_epoch, c=None, until=None):
    """统计 EMQX 容器在 [since_epoch, until) 内的日志中匹配 pattern（扩展正则）的行数；无法读取返回 None。

    EMQX 与应用不在同一台主机时（拆分部署），经压测机上的 docker CLI 访问 config.json 中
    emqx_docker（如 tcp://<EMQX 主机>:2375）；未配置则读压测机本机的 conduit-emqx。
    时间锚点必须带 Z：主机时区为 CST 时，不带时区的时间会被 Docker 按本地时间解释，多截 8 小时。
    """
    host = config().get('emqx_docker')
    z = lambda t: time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(t))
    cmd = "docker {} logs --since {}{} conduit-emqx 2>&1 | grep -cE '{}'".format(
        '-H ' + host if host else '', z(since_epoch), ' --until ' + z(until) if until else '', pattern)
    rc, o = run(cmd, timeout=300, c=c)
    try:
        return int(o.strip().split()[-1])
    except Exception:
        return None
