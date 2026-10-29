"""Isolated durability experiment. Password is read only from the environment."""
import os, sys, socket, time, json, threading, pathlib, base64, argparse
import paramiko
import paho.mqtt.client as mqtt
from paho.mqtt.properties import Properties
from paho.mqtt.packettypes import PacketTypes

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('kind',choices=['process','guest-reset'])
parser.add_argument('--host',required=True)
parser.add_argument('--user',default='root')
parser.add_argument('--host-key',required=True,help='Verified SSH ED25519 public key, base64 field only')
parser.add_argument('--expected-machine-id',help='Required for emergency guest reset; verify manually first')
parser.add_argument('--rounds',type=int,default=3)
parser.add_argument('--output-dir',type=pathlib.Path,default=pathlib.Path('target/durability'))
args=parser.parse_args()
if args.rounds < 1:parser.error('--rounds must be positive')
if args.kind=='guest-reset' and not args.expected_machine_id:parser.error('guest-reset requires --expected-machine-id')
HOST=args.host
PIN=args.host_key
OUTPUT_DIR=args.output_dir
OUTPUT_DIR.mkdir(parents=True,exist_ok=True)
def ssh():
    c=paramiko.SSHClient()
    c.get_host_keys().add(HOST,'ssh-ed25519',paramiko.Ed25519Key(data=base64.b64decode(PIN)))
    c.connect(HOST,username=args.user,password=os.environ['CONDUIT_SSH_PASSWORD'],timeout=5)
    return c
def remote(c, cmd):
    _,out,err=c.exec_command(cmd,timeout=120)
    data=out.read().decode(); error=err.read().decode()
    code=out.channel.recv_exit_status()
    if code: raise RuntimeError((cmd,code,error,data))
    return data.strip()
class Redis:
    def __init__(self,port):
        self.s=socket.create_connection((HOST,port),timeout=5); self.f=self.s.makefile('rb')
    def cmd(self,*args):
        parts=[str(x).encode() for x in args]
        self.s.sendall(b'*'+str(len(parts)).encode()+b'\r\n'+b''.join(b'$'+str(len(x)).encode()+b'\r\n'+x+b'\r\n' for x in parts))
        return self.read()
    def read(self):
        l=self.f.readline()
        if not l:raise EOFError()
        t,v=l[:1],l[1:-2]
        if t==b'-':raise RuntimeError(v)
        if t==b':':return int(v)
        if t==b'+':return v.decode()
        if t==b'$':
            n=int(v)
            if n<0:return None
            data=self.f.read(n); self.f.read(2);return data.decode()
        if t==b'*':return [self.read() for _ in range(int(v))]
        raise RuntimeError(l)
    def close(self):self.f.close();self.s.close()
def client(cid,handler=None):
    c=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,client_id=cid,protocol=mqtt.MQTTv5)
    c.connected_event=threading.Event(); c.sub_event=threading.Event(); c.was_present=False
    def connected(cl,ud,flags,reason,props):
        if reason.is_failure: raise RuntimeError(str(reason))
        cl.was_present=flags.session_present;cl.connected_event.set()
    c.on_connect=connected
    c.on_subscribe=lambda *args:c.sub_event.set()
    if handler:c.on_message=handler
    p=Properties(PacketTypes.CONNECT);p.SessionExpiryInterval=86400
    c.connect(HOST,21883,keepalive=30,clean_start=False,properties=p);c.loop_start()
    if not c.connected_event.wait(15):raise RuntimeError('MQTT connect timeout')
    return c
def wait_port(port,timeout=120):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        try:
            with socket.create_connection((HOST,port),timeout=1):return
        except OSError:time.sleep(1)
    raise TimeoutError(port)
def journal(path,data):
    with open(path,'w',encoding='utf-8') as f:
        json.dump(data,f,indent=2);f.flush();os.fsync(f.fileno())
def trial(kind, index):
    run=f'{kind}-{index}-{time.time_ns()}'
    c=ssh(); wait_port(21883)
    topic='durability/'+run; cid='durability-'+run
    sub=client(cid);sub.subscribe(topic,1)
    if not sub.sub_event.wait(10):raise RuntimeError('SUBACK timeout')
    sub.disconnect();sub.loop_stop();time.sleep(2)
    rs=[Redis(26379),Redis(26380)]
    key='durability:'+run
    for seq in range(1,2001):
        for r in rs:r.cmd('XADD',key,'*','seq',seq,'payload','x'*256)
    # MQTT is sent last so an immediate kill probes its asynchronous buffer.
    pub=client('publisher-'+run)
    errors=[]
    pub.on_publish=lambda cl,ud,mid,reason,props: errors.append(str(reason)) if reason.is_failure else None
    for seq in range(1,501):
        info=pub.publish(topic,str(seq),qos=1);info.wait_for_publish(timeout=10)
        if not info.is_published():raise RuntimeError('PUBACK timeout')
    if errors:raise RuntimeError(errors)
    expected_count=2000
    if kind=='guest-reset':
        machine=remote(c,'cat /etc/machine-id'); oldboot=remote(c,'cat /proc/sys/kernel/random/boot_id')
        if machine!=args.expected_machine_id:raise RuntimeError('Unexpected VM; refusing reset')
        # Add a fresh acknowledged Redis tail immediately before the reset.
        for seq in range(2001,2101):
            for r in rs:r.cmd('XADD',key,'*','seq',seq,'payload','x'*256)
        expected_count=2100
    expected={'run':run,'redis_acked':expected_count,'mqtt_pubacked':500,'mqtt_publish_errors':errors}
    journal(OUTPUT_DIR/(run+'-acked.json'),expected)
    if kind=='process':
        remote(c,'docker kill -s KILL conduit-durability-emqx conduit-durability-redis-everysec conduit-durability-redis-always >/dev/null; docker start conduit-durability-emqx conduit-durability-redis-everysec conduit-durability-redis-always >/dev/null')
    else:
        # Linux emergency reset, no sync/unmount: destroys guest page cache.
        # Does NOT simulate loss of hypervisor/physical disk write caches.
        try:c.exec_command('echo b > /proc/sysrq-trigger',timeout=5)
        except Exception:pass
        c.close();time.sleep(8)
        end=time.monotonic()+180
        while time.monotonic()<end:
            try:
                c=ssh()
                if remote(c,'cat /proc/sys/kernel/random/boot_id')!=oldboot:break
                c.close()
            except Exception:pass
            time.sleep(3)
        else:raise TimeoutError('VM did not restart')
    try:pub.disconnect();pub.loop_stop()
    except Exception:pass
    for r in rs:r.close()
    wait_port(26379);wait_port(26380);wait_port(21883)
    actual={}
    for label,port in [('everysec',26379),('always',26380)]:
        r=Redis(port);rows=r.cmd('XRANGE',key,'-','+');r.close()
        seen={int(dict(zip(row[1][::2],row[1][1::2]))['seq']) for row in rows}
        missing=sorted(set(range(1,expected_count+1))-seen)
        actual[label]={'received':len(seen),'missing_count':len(missing),'missing_first':missing[:30]}
    seen=set();last=[time.monotonic()]
    def message(cl,ud,msg):seen.add(int(msg.payload));last[0]=time.monotonic()
    sub=client(cid,message)
    present=sub.was_present
    # Do not resubscribe: session survival is part of the assertion.
    end=time.monotonic()+35
    while len(seen)<500 and time.monotonic()<end:time.sleep(.1)
    sub.disconnect();sub.loop_stop()
    missing=sorted(set(range(1,501))-seen)
    actual['emqx']={'session_present':present,'received':len(seen),'missing_count':len(missing),'missing_first':missing[:40]}
    actual.update(expected)
    journal(OUTPUT_DIR/(run+'-result.json'),actual)
    print(json.dumps(actual),flush=True);c.close()
if __name__=='__main__':
    for i in range(args.rounds):trial(args.kind,i+1)
