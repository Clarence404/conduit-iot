"""压测机远程执行助手：SSH 执行命令、上传文件、查 Prometheus。

凭据只从同目录的 config.json 读取（已在 .gitignore 中），绝不硬编码、不打印。

依赖：pip install paramiko
"""
import json
import pathlib
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


def prom(expr, c=None):
    """查 Prometheus 的瞬时值，返回所有非 NaN 结果之和；无数据返回 None。

    注意用 POST 并对表达式做 URL 编码：表达式里的 `+` 在 form-urlencoded 中
    会被解析成空格，直接拼进 GET 查询串会把表达式拆坏（实测踩过）。
    """
    enc = urllib.parse.quote(expr, safe='')
    rc, o = run(f"docker exec conduit-prometheus-1 wget -qO- --post-data='query={enc}' "
                f"'http://localhost:9090/api/v1/query' 2>/dev/null", timeout=200, c=c)
    try:
        d = json.loads(o.strip())
        if d.get('status') != 'success':
            return None
        vals = [float(x['value'][1]) for x in d['data']['result'] if x['value'][1] != 'NaN']
        return sum(vals) if vals else None
    except Exception:
        return None
