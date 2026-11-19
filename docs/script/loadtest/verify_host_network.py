"""Validate the host-network overlay with real Docker Compose, without starting services.

Uses the existing laboratory SSH configuration; uploads only tracked deployment
templates and dummy credentials to a temporary directory. Never reads VM .env files.
"""
import json
import pathlib
import shlex

import remote

ROOT = pathlib.Path(__file__).resolve().parents[3]
FILES = (
    'deploy/dev/docker-compose.yml',
    'deploy/split/emqx/docker-compose.yml',
    'deploy/split/emqx/docker-compose.host.yml',
)


def main():
    client = remote.connect()
    temporary = None
    try:
        code, output = remote.run('mktemp -d /tmp/conduit-host-verify.XXXXXXXX', c=client)
        if code:
            raise RuntimeError('Cannot create temporary Compose validation directory')
        temporary = output.strip()
        if not temporary.startswith('/tmp/conduit-host-verify.') or '/' in temporary[len('/tmp/'):]:
            raise RuntimeError('Unexpected temporary directory')
        sftp = client.open_sftp()
        try:
            for directory in ('deploy', 'deploy/dev', 'deploy/split', 'deploy/split/emqx'):
                sftp.mkdir(temporary + '/' + directory)
            for file in FILES:
                sftp.put(str(ROOT / file), temporary + '/' + file)
        finally:
            sftp.close()
        env = {
            'POSTGRES_PASSWORD': 'validation-only', 'INFLUX_TOKEN': 'validation-only',
            'INFLUX_EXPLORER_SESSION_KEY': 'validation-only',
            'CONDUIT_AUTH_URL': 'http://validation.invalid:8091',
            'CONDUIT_INTERNAL_TOKEN': 'validation-only',
            'CONDUIT_EMQX_API_KEY': 'validation-only',
            'CONDUIT_EMQX_API_SECRET': 'validation-only',
            'EMQX_DASHBOARD_PASSWORD': 'validation-only',
        }
        checks = 0
        for address, mqtt, internal, dashboard in (('127.0.0.1', 1883, 1884, 18083), ('0.0.0.0', 31883, 31884, 38083)):
            variables = {**env, 'BIND_ADDR': address, 'MQTT_PORT': str(mqtt), 'MQTT_INTERNAL_PORT': str(internal),
                         'EMQX_DASHBOARD_PORT': str(dashboard)}
            prefix = ' '.join(k + '=' + shlex.quote(v) for k, v in variables.items())
            for host in (False, True):
                files = '-f docker-compose.yml' + (' -f docker-compose.host.yml' if host else '')
                code, output = remote.run(
                    'cd ' + shlex.quote(temporary + '/deploy/split/emqx') + ' && ' + prefix
                    + ' docker compose ' + files + ' config --format json', c=client)
                if code:
                    # Compose diagnostic contains only synthetic values used above.
                    raise RuntimeError(output)
                service = json.loads(output)['services']['emqx']
                if host:
                    assert service['network_mode'] == 'host'
                    assert not service.get('ports')
                    assert not service.get('networks')
                    assert not service.get('sysctls')
                    values = service['environment']
                    assert values['EMQX_LISTENERS__TCP__DEFAULT__BIND'] == f'{address}:{mqtt}'
                    assert values['EMQX_DASHBOARD__LISTENERS__HTTP__BIND'] == f'{address}:{dashboard}'
                    assert values['EMQX_LISTENERS__TCP__INTERNAL__BIND'] == f'{address}:{internal}'
                    for protocol in ('SSL', 'WS', 'WSS'):
                        assert values[f'EMQX_LISTENERS__{protocol}__DEFAULT__ENABLE'] == 'false'
                else:
                    assert service.get('network_mode') != 'host'
                    # 设备 MQTT、transport 内部监听器（2026-11-16 起）、Dashboard
                    assert len(service['ports']) == 3
                    assert service['sysctls']
                assert service['container_name'] == 'conduit-emqx'
                assert service['image'] == 'emqx/emqx:5.10.5'
                checks += 1
                print(f'PASS {"host" if host else "bridge"}: {address}, ports {mqtt}/{internal}/{dashboard}')
        print(f'{checks} merged Compose configurations validated; no services started.')
    finally:
        if temporary and temporary.startswith('/tmp/conduit-host-verify.') and '/' not in temporary[len('/tmp/'):]:
            remote.run('rm -r -- ' + shlex.quote(temporary), c=client)
        client.close()


if __name__ == '__main__':
    main()
