#!/usr/bin/env python3
"""Sequential CLI workers with snapshot-bound QA, validation and commit gates."""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
RUNS = ROOT / '.ai-runs'
CONFIG = ROOT / 'scripts/ai/config.json'


class NativePending(RuntimeError):
    """A native Codex App agent must answer the generated request."""


def native_exchange(stage, directory, model, prompt, schema, connectivity_label=None):
    request_path = directory / (stage + '-native-request.json')
    response_path = directory / (stage + '-native-result.json')
    current = snapshot()
    request = json.loads(request_path.read_text()) if request_path.exists() else {}
    if (request.get('snapshot') != current or request.get('model') != model or
            request.get('expires_at', 0) < time.time() or request.get('source_prompt') != prompt or
            (not connectivity_label and request.get('consumed'))):
        nonce = uuid.uuid4().hex
        request = {'transport': 'codex-app', 'stage': stage, 'model': model,
                   'snapshot': current, 'nonce': nonce, 'expires_at': time.time() + 1800,
                   'source_prompt': prompt, 'prompt': prompt, 'output_schema': schema}
        if connectivity_label:
            request['expected_response'] = connectivity_label + '_READY ' + nonce
            request['prompt'] = ('Connectivity check only. Do not read files or use tools. '
                                 'Return a JSON object with response exactly: ' + request['expected_response'])
        save(request_path, request)
        response_path.unlink(missing_ok=True)
    if not response_path.exists():
        raise NativePending(f'Dispatch {model} through Codex App native agents using {request_path}; '
                            f'save the authentic result envelope to {response_path}, then repeat this command.')
    response = json.loads(response_path.read_text())
    for key in ('transport', 'stage', 'model', 'snapshot', 'nonce'):
        if response.get(key) != request[key]:
            raise RuntimeError(f'Native worker {key} does not match its pending request')
    if not isinstance(response.get('agent_task'), str) or not response['agent_task'].startswith('/root/'):
        raise RuntimeError('Native result must identify the dispatched Codex App agent task')
    if not isinstance(response.get('result'), dict):
        raise RuntimeError('Native result envelope must contain the actual agent JSON response')
    if not connectivity_label:
        history = directory / 'native-history'
        history.mkdir(exist_ok=True)
        save(history / (stage + '-' + request['nonce'] + '.json'), {'request': request, 'response': response})
        request['consumed'] = True
        save(request_path, request)
    return response['result']


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def snapshot():
    """Hash HEAD plus every tracked/nonignored untracked path, mode and content.

    Index staging alone does not change the snapshot; deletions and symlinks do.
    Ignored build output and reports intentionally do not participate.
    """
    digest = hashlib.sha256(git('rev-parse', 'HEAD').strip())
    paths = sorted(set(git('ls-files', '--cached', '--others', '--exclude-standard', '-z').split(b'\0')) - {b''})
    for raw in paths:
        path = ROOT / os.fsdecode(raw)
        digest.update(len(raw).to_bytes(8, 'big') + raw)
        if not path.exists() and not path.is_symlink():
            digest.update(b'DELETED')
            continue
        mode = path.lstat().st_mode
        digest.update(str(stat.S_IMODE(mode)).encode() + b'\0')
        if path.is_symlink():
            digest.update(b'LINK' + os.fsencode(os.readlink(path)))
        elif path.is_file():
            digest.update(b'FILE' + hashlib.sha256(path.read_bytes()).digest())
        else:
            raise RuntimeError(f'Unsupported tracked path: {path}')
    return digest.hexdigest()


def save(path, value):
    tmp = path.with_suffix('.tmp')
    tmp.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')
    tmp.replace(path)


@contextlib.contextmanager
def lock():
    RUNS.mkdir(exist_ok=True)
    path = RUNS / 'workflow.lock'
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.write(fd, str(os.getpid()).encode())
    os.close(fd)
    try:
        yield
    finally:
        path.unlink()


def execute(argv, cwd, log, timeout, extra_env=None):
    env = dict(os.environ)
    env.setdefault('MAVEN_USER_HOME', str(ROOT / '.tools/m2'))
    jdk = ROOT / '.tools/jdk-27/Contents/Home'
    if jdk.exists():
        env['JAVA_HOME'] = str(jdk)
        env['PATH'] = str(jdk / 'bin') + os.pathsep + env.get('PATH', '')
    env.update(extra_env or {})
    with log.open('w') as stream:
        stream.write(json.dumps({'argv': argv, 'cwd': str(cwd)}, ensure_ascii=False) + '\n')
        stream.flush()
        try:
            child = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                     stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
        except OSError as error:
            stream.write(str(error) + '\n')
            return 127
        try:
            return child.wait(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
            stream.write('\nINTERRUPTED OR TIMED OUT\n')
            return 124


def require(state, stage):
    record = state.get(stage, {})
    if record.get('verdict') != 'PASS' or record.get('snapshot') != snapshot():
        raise RuntimeError(f'{stage} must PASS on the current snapshot; rerun from verification after edits.')


def is_sha256(value):
    return isinstance(value, str) and bool(re.fullmatch(r'[0-9a-f]{64}', value))


def is_valid_marker(marker, base, initial_snapshot):
    return (isinstance(marker, dict) and
            set(marker.keys()) == {'version', 'base', 'initial_snapshot'} and
            marker.get('version') == 1 and type(marker.get('version')) is int and
            marker.get('base') == base and isinstance(base, str) and
            marker.get('initial_snapshot') == initial_snapshot and is_sha256(initial_snapshot))


def is_valid_implement_record(record):
    return (isinstance(record, dict) and
            record.get('verdict') == 'PASS' and
            is_sha256(record.get('snapshot')))


def test_evidence(name, log):
    """Reject empty or skipped required suites, even when the command exits zero."""
    text = log.read_text()
    if name == 'backend':
        summaries = re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)', text)
        return bool(summaries) and int(summaries[-1][0]) > 0 and all(
            int(failures) == int(errors) == int(skipped) == 0 for _, failures, errors, skipped in summaries)
    if name == 'frontend-unit':
        count = re.search(r'[ℹ#]\s+tests\s+(\d+)', text)
        skipped = re.search(r'[ℹ#]\s+skipped\s+(\d+)', text)
        return bool(count and int(count[1]) > 0 and skipped and int(skipped[1]) == 0)
    if name == 'frontend-e2e':
        count = re.search(r'(\d+) passed', text)
        return bool(count and int(count[1]) > 0 and not re.search(r'[1-9]\d* (?:skipped|did not run)', text))
    if name == 'workflow-tests':
        return bool(re.search(r'Ran [1-9]\d* tests?', text) and re.search(r'^OK\s*$', text, re.M)
                    and not re.search(r'skipped=[1-9]', text))
    return True


def agy_path():
    local = ROOT / '.tools/ai-workflow/agy'
    return str(local) if local.is_file() else shutil.which('agy')


def revoke_native(directory, *stages):
    for stage in stages:
        for suffix in ('-native-request.json', '-native-result.json'):
            (directory / (stage + suffix)).unlink(missing_ok=True)


def worker(stage, directory, config, prompt):
    model = config['models'][stage]
    output = directory / (stage + '-result.json')
    output.unlink(missing_ok=True)
    log = directory / (stage + '.log')
    if stage == 'implement':
        binary = agy_path()
        if not binary:
            raise RuntimeError('Antigravity CLI missing; see docs/ai-workflow.md')
        argv = [binary, '--log-file', str(directory / 'implement-runtime.log'),
                '--model', model, '--mode', 'accept-edits', '--sandbox',
                '-p', prompt, '--output-format', 'json']
    else:
        schema = directory / 'report-schema.json'
        schema_value = {'type': 'object', 'additionalProperties': False, 'properties': {
            'verdict': {'type': 'string', 'enum': ['PASS', 'FAIL']},
            'reviewed_snapshot': {'type': 'string'},
            'scope': {'type': 'string'},
            'findings': {'type': 'array', 'items': {'type': 'string'}},
            'blockers': {'type': 'array', 'items': {'type': 'string'}}},
            'required': ['verdict', 'reviewed_snapshot', 'scope', 'findings', 'blockers']}
        save(schema, schema_value)
        if config.get('worker_transports', {}).get(stage) == 'codex-app':
            result = native_exchange(stage, directory, model, prompt, schema_value)
            validate_report(result)
            save(output, result)
            return result
        if config.get('worker_transports', {}).get(stage) == 'antigravity-cli':
            binary = agy_path()
            if not binary:
                raise RuntimeError('Antigravity CLI missing; see docs/ai-workflow.md')
            argv = [binary, '--log-file', str(directory / f'{stage}-runtime.log'),
                    '--model', model, '--mode', 'plan', '--sandbox',
                    '--json-schema', str(schema), '--output-format', 'json',
                    '-p', prompt]
        else:
            argv = ['codex', 'exec', '--ephemeral', '-m', model, '-s', 'read-only',
                    '--output-schema', str(schema), '-o', str(output), prompt]
    code = execute(argv, ROOT, log, config['timeout_seconds'])
    if code:
        raise RuntimeError(f'{stage} failed ({code}); inspect {log}')
    if stage == 'implement':
        # CLI stdout includes diagnostic text on some versions. Preserve it all,
        # but accept only an explicit terminal SUCCESS envelope.
        raw = log.read_text()
        result = None
        decoder = json.JSONDecoder()
        for match in re.finditer(r'\{', raw):
            try:
                item, _ = decoder.raw_decode(raw[match.start():])
                if isinstance(item, dict) and 'status' in item:
                    result = item
            except ValueError:
                pass
        if not result or result.get('status') != 'SUCCESS' or not result.get('response'):
            raise RuntimeError('Flash did not return an explicit SUCCESS response; inspect implement.log')
        if result.get('denied_actions') or re.search(r'soft.denied|permission denied|requires approval', raw, re.I):
            raise RuntimeError('Flash encountered blocked actions; implementation is incomplete')
        save(output, result)
        return result
    if config.get('worker_transports', {}).get(stage) == 'antigravity-cli':
        raw = log.read_text()
        result = None
        decoder = json.JSONDecoder()
        for match in re.finditer(r'\{', raw):
            try:
                item, _ = decoder.raw_decode(raw[match.start():])
                if isinstance(item, dict) and 'verdict' in item:
                    result = item
            except ValueError:
                pass
        if not result:
            for match in re.finditer(r'\{', raw):
                try:
                    item, _ = decoder.raw_decode(raw[match.start():])
                    if isinstance(item, dict) and 'response' in item:
                        resp = item['response']
                        if isinstance(resp, str):
                            try:
                                inner = json.loads(resp)
                                if isinstance(inner, dict) and 'verdict' in inner:
                                    result = inner
                            except Exception:
                                pass
                except ValueError:
                    pass
        if not result:
            raise RuntimeError(f'{stage} did not produce a valid report JSON; inspect {log}')
        validate_report(result)
        save(output, result)
        return result
    result = json.loads(output.read_text())
    validate_report(result)
    return result


def validate_report(result):
    if set(result) != {'verdict', 'reviewed_snapshot', 'scope', 'findings', 'blockers'}:
        raise RuntimeError('Worker report has missing or unexpected fields')
    if result.get('verdict') not in ('PASS', 'FAIL') or not isinstance(result.get('blockers'), list):
        raise RuntimeError('Worker report has an invalid contract')
    if (not isinstance(result.get('scope'), str) or not result['scope'].strip() or
            not isinstance(result.get('reviewed_snapshot'), str) or not isinstance(result.get('findings'), list)):
        raise RuntimeError('Worker report is missing scope/findings')
    if not all(isinstance(item, str) for item in result['findings'] + result['blockers']):
        raise RuntimeError('Worker report findings/blockers must be strings')


def run(args):
    config = json.loads(CONFIG.read_text())
    for stage, transport in config.get('worker_transports', {}).items():
        allowed = ('antigravity-cli', 'codex-app', 'codex-cli')
        if stage not in ('implement', 'qa', 'review') or transport not in allowed:
            raise RuntimeError(f'Unsupported worker transport: {stage}={transport}')
    if args.action == 'doctor':
        print(json.dumps({'codex': shutil.which('codex'), 'agy': agy_path(),
                          'models': config['models'], 'worker_transports': config.get('worker_transports', {}),
                          'note': 'Paths do not establish model access; run smoke. Exit 2 requires native Codex App dispatch.'}, indent=2))
        return
    with lock():
        if args.action == 'smoke':
            if args.task and not re.fullmatch(r'smoke-[0-9]+', args.task):
                raise RuntimeError('Resume smoke with its exact smoke-<number> ID')
            directory = RUNS / (args.task or ('smoke-' + str(time.time_ns())))
            if args.task and not directory.is_dir():
                raise RuntimeError('Smoke ID does not exist')
            directory.mkdir(exist_ok=True)
            codes = {}
            pending = []
            request_snapshot = snapshot()
            smoke_workers = [
                ('flash', 'implement', 'FLASH_READY'),
                ('luna', 'qa', 'LUNA_READY'),
                ('sol', 'review', 'SOL_READY'),
            ]
            for name, stage, ready_text in smoke_workers:
                transport = config.get('worker_transports', {}).get(stage, 'antigravity-cli' if name == 'flash' else 'codex-app')
                if transport == 'antigravity-cli':
                    argv = [agy_path() or 'agy', '--log-file', str(directory / f'{name}-runtime.log'),
                            '--model', config['models'][stage], '-p',
                            f'Connectivity only. Do not use tools. Reply exactly {ready_text}.', '--output-format', 'json']
                else:
                    argv = ['codex', 'exec', '--ephemeral', '-m', config['models'][stage], '-s', 'read-only',
                            '-o', str(directory / f'{name}-response.txt'),
                            f'Connectivity only. Do not use tools. Reply exactly {ready_text}.']
                if transport == 'codex-app':
                    try:
                        result = native_exchange(stage, directory, config['models'][stage],
                                                 'Connectivity only', {'type': 'object', 'properties': {
                                                     'response': {'type': 'string'}}, 'required': ['response'],
                                                     'additionalProperties': False}, name.upper())
                        req = json.loads((directory / (stage + '-native-request.json')).read_text())
                        ready = result.get('response') == req['expected_response']
                        codes[name] = {'ready': ready, 'transport': 'codex-app', 'model': config['models'][stage]}
                    except NativePending as error:
                        pending.append(str(error))
                        codes[name] = {'ready': False, 'transport': 'codex-app', 'status': 'AWAITING_NATIVE_AGENT'}
                    continue
                code = execute(argv, ROOT, directory / (name + '.log'), 45)
                content = (directory / (name + '.log')).read_text().split('\n', 1)[-1]
                if transport == 'antigravity-cli':
                    ready = False
                    for match in re.finditer(r'\{', content):
                        try:
                            item, _ = json.JSONDecoder().raw_decode(content[match.start():])
                            if isinstance(item, dict) and item.get('status') == 'SUCCESS':
                                ready = item.get('response', '').strip() == ready_text
                        except ValueError:
                            pass
                else:
                    response = directory / (name + '-response.txt')
                    ready = response.exists() and response.read_text().strip() == ready_text
                codes[name] = {'exit_code': code, 'ready': code == 0 and ready,
                               'transport': transport,
                               'model': config['models'][stage]}
            if snapshot() != request_snapshot:
                raise RuntimeError('Source changed during connectivity checks; rerun smoke')
            save(directory / 'results.json', codes)
            print(json.dumps(codes, indent=2)); print(directory)
            if pending:
                raise NativePending('\n'.join(pending))
            if not all(item['ready'] for item in codes.values()):
                raise RuntimeError('Live model smoke did not pass for every worker')
            return
        if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}', args.task):
            raise RuntimeError('Task ID must be a simple name (letters, digits, dash, underscore).')
        directory = RUNS / args.task
        statefile = directory / 'state.json'
        if args.action == 'start':
            if directory.exists():
                raise RuntimeError('Task already exists; use a new ID')
            if not args.audit and git('status', '--porcelain').strip():
                raise RuntimeError('Normal implementation requires a clean worktree. Use --audit for existing code QA only.')
            plan = Path(args.plan).read_text()
            if not plan.strip():
                raise RuntimeError('Plan must contain requirements and acceptance criteria')
            directory.mkdir()
            (directory / 'plan.md').write_text(plan)
            save(statefile, {'base': git('rev-parse', 'HEAD').decode().strip(),
                             'audit': args.audit, 'initial_snapshot': snapshot()})
            print(directory)
            return
        state = json.loads(statefile.read_text())
        if args.action == 'status':
            print(json.dumps(state, indent=2)); print('current_snapshot:', snapshot()); return
        if args.action == 'commit':
            if state['audit']:
                raise RuntimeError('Audit tasks cannot commit')
            for stage in ('verify', 'qa', 'review'):
                require(state, stage)
            if not args.message or not args.message.strip():
                raise RuntimeError('Commit message is required')
            subprocess.run(['git', 'add', '-A'], cwd=ROOT, check=True)
            # Detect changes between authorization and staging; never commit stale evidence.
            require(state, 'review')
            subprocess.run(['git', 'diff', '--exit-code'], cwd=ROOT, check=True)
            subprocess.run(['git', 'commit', '-m', args.message], cwd=ROOT, check=True)
            state['commit'] = git('rev-parse', 'HEAD').decode().strip()
            save(statefile, state); print(state['commit']); return
        before = snapshot()
        if args.action == 'implement':
            if state['audit']:
                raise RuntimeError('Audit tasks cannot implement')
            if git('rev-parse', 'HEAD').decode().strip() != state['base']:
                raise RuntimeError('HEAD changed since planning; start a new task')
            if 'implement_attempt' in state:
                marker = state['implement_attempt']
                if not is_valid_marker(marker, state.get('base'), state.get('initial_snapshot')):
                    raise RuntimeError('Malformed implement_attempt marker')
            elif is_valid_implement_record(state.get('implement')):
                if not isinstance(state.get('base'), str) or not is_sha256(state.get('initial_snapshot')):
                    raise RuntimeError('Malformed state base or initial_snapshot')
                marker = {'version': 1, 'base': state['base'], 'initial_snapshot': state['initial_snapshot']}
            elif 'implement' in state:
                raise RuntimeError('Invalid implement record')
            elif before == state.get('initial_snapshot'):
                if not isinstance(state.get('base'), str) or not is_sha256(state.get('initial_snapshot')):
                    raise RuntimeError('Malformed state base or initial_snapshot')
                marker = {'version': 1, 'base': state['base'], 'initial_snapshot': state['initial_snapshot']}
            else:
                raise RuntimeError('Workspace changed since planning; start a new task')
            feedback = '\n'.join((directory / (s + '-result.json')).read_text() for s in ('qa', 'review')
                                 if (directory / (s + '-result.json')).exists())
            new_state = dict(state)
            for stage in ('implement', 'verify', 'qa', 'review'):
                new_state.pop(stage, None)
            new_state['implement_attempt'] = marker
            save(statefile, new_state)
            state = new_state
            revoke_native(directory, 'qa', 'review')
            worker('implement', directory, config,
                   f'Read AGENTS.md and {directory / "plan.md"}. Implement only the plan. '
                   f'Fix these findings if present: {feedback}. Add meaningful tests. '
                   'Do not commit, push, change workflow gates/configuration, credentials or ignored files. '
                   'Return actual changes and any blockers. Do not claim tests passed without running them.')
            if git('rev-parse', 'HEAD').decode().strip() != state['base']:
                raise RuntimeError('Worker changed HEAD; refusing to certify implementation')
            state['implement'] = {'verdict': 'PASS', 'snapshot': snapshot()}
        elif args.action == 'verify':
            if not state.get('audit') and not is_valid_implement_record(state.get('implement')):
                raise RuntimeError('Run implement before verification')
            for stage in ('verify', 'qa', 'review'):
                state.pop(stage, None)
            save(statefile, state)
            revoke_native(directory, 'qa', 'review')
            checks = []
            for check in config['checks']:
                print('Running', check['name'], flush=True)
                code = execute(check['argv'], ROOT / check['cwd'], directory / (check['name'] + '.log'), config['timeout_seconds'])
                evidence = code == 0 and test_evidence(check['name'], directory / (check['name'] + '.log'))
                checks.append({'name': check['name'], 'exit_code': code, 'evidence_valid': evidence})
            passed = bool(checks) and all(c['exit_code'] == 0 and c['evidence_valid'] for c in checks) and before == snapshot()
            state['verify'] = {'verdict': 'PASS' if passed else 'FAIL', 'snapshot': before, 'checks': checks}
        else:
            require(state, 'verify')
            if args.action == 'review':
                require(state, 'qa')
            state.pop(args.action, None)
            if args.action == 'qa':
                state.pop('review', None)
            save(statefile, state)
            if args.action == 'qa':
                revoke_native(directory, 'review')
            prompt = (f'Read AGENTS.md, the ENTIRE project source/tests/config/docs (excluding credentials, dependencies, '
                      f'generated files and .env), {directory / "plan.md"}, and validation logs in {directory}. '
                      f'Review ALL current changes against base {state["base"]}, including untracked files. '
                      'Distinguish pre-existing issues from regressions. Assess correctness, architecture, security, '
                      'maintainability, test coverage and full project health. Inspect actual test logs and test code; '
                      'report skipped tests, missing coverage or blocked actions. Never turn missing evidence into PASS. '
                      'Do not edit files, commit or push. Return FAIL if any blocking finding or required check is missing. '
                      f'Return reviewed_snapshot exactly {before}. '
                      + ('Act as independent Luna QA.' if args.action == 'qa' else
                         f'Act as final Sol reviewer. Independently verify Luna findings in {directory / "qa-result.json"}.'))
            result = worker(args.action, directory, config, prompt)
            passed = (result['verdict'] == 'PASS' and not result['blockers'] and
                      result.get('reviewed_snapshot') == before and snapshot() == before)
            state[args.action] = {'verdict': 'PASS' if passed else 'FAIL', 'snapshot': before, 'report': result}
        save(statefile, state)
        print(json.dumps(state[args.action], indent=2))
        if state[args.action]['verdict'] != 'PASS':
            raise RuntimeError(f'{args.action} failed; commit remains blocked')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['doctor', 'smoke', 'start', 'implement', 'verify', 'qa', 'review', 'commit', 'status'])
    parser.add_argument('task', nargs='?', default='')
    parser.add_argument('--plan')
    parser.add_argument('--audit', action='store_true')
    parser.add_argument('--message')
    args = parser.parse_args()
    if args.action == 'start' and not args.plan:
        parser.error('start requires --plan')
    try:
        run(args)
    except NativePending as error:
        print(f'AWAITING_CODEX_APP: {error}', file=sys.stderr)
        return 2
    except (RuntimeError, OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f'BLOCKED: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
