import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('workflow', Path(__file__).resolve().parents[2] / 'scripts/ai/workflow.py')
w = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(w)


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.patches = [patch.object(w, 'ROOT', self.root), patch.object(w, 'RUNS', self.root / '.ai-runs'),
                        patch.object(w, 'CONFIG', self.root / 'config.json')]
        for p in self.patches:
            p.start()
        self.git('init', '-q')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'user.name', 'Workflow Test')
        (self.root / '.gitignore').write_text('.ai-runs/\n')
        (self.root / 'source.txt').write_text('original\n')
        w.save(w.CONFIG, {'models': {'implement': 'flash', 'qa': 'luna', 'review': 'sol'},
                          'timeout_seconds': 5, 'checks': [{'name': 'check', 'cwd': '.',
                                                          'argv': ['python3', '-c', 'print("ok")']}]})
        self.git('add', '.')
        self.git('commit', '-qm', 'baseline')
        w.RUNS.mkdir()
        self.plan = w.RUNS / 'plan.md'
        self.plan.write_text('Audit the whole project and all changes; require checks to pass.')

    def tearDown(self):
        for p in reversed(self.patches):
            p.stop()
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, stderr=subprocess.STDOUT)

    def run_stage(self, action, audit=False):
        return w.run(argparse.Namespace(action=action, task='test', plan=str(self.plan), audit=audit, message='validated'))

    def state(self):
        return json.loads((w.RUNS / 'test/state.json').read_text())

    def result(self, stage, directory, config, prompt):
        return {'verdict': 'PASS', 'reviewed_snapshot': w.snapshot(), 'scope': 'whole project and diff',
                'findings': [], 'blockers': []}

    def prepare_audit(self):
        self.run_stage('start', audit=True)
        self.run_stage('verify')

    def test_real_check_and_mocked_reviews_pass(self):
        self.prepare_audit()
        with patch.object(w, 'worker', side_effect=self.result):
            self.run_stage('qa'); self.run_stage('review')
        self.assertEqual(self.state()['review']['verdict'], 'PASS')

    def test_full_lifecycle_gated_commit(self):
        self.run_stage('start')
        with patch.object(w, 'worker', side_effect=self.result):
            self.run_stage('implement')
            (self.root / 'source.txt').write_text('implemented\n')
            self.run_stage('verify'); self.run_stage('qa'); self.run_stage('review')
        self.run_stage('commit')
        self.assertEqual(self.git('log', '-1', '--format=%s').strip(), b'validated')

    def test_untracked_content_mode_delete_and_head_invalidate(self):
        baseline = w.snapshot()
        p = self.root / 'new.txt'; p.write_text('new')
        self.assertNotEqual(baseline, w.snapshot()); p.unlink()
        p = self.root / 'source.txt'; p.chmod(0o755)
        self.assertNotEqual(baseline, w.snapshot()); p.chmod(0o644)
        p.unlink(); self.assertNotEqual(baseline, w.snapshot()); p.write_text('original\n')
        self.git('commit', '--allow-empty', '-qm', 'new head')
        self.assertNotEqual(baseline, w.snapshot())

    def test_missing_qa_blocks_review(self):
        self.prepare_audit()
        with self.assertRaises(RuntimeError): self.run_stage('review')

    def test_source_edit_blocks_qa(self):
        self.prepare_audit()
        (self.root / 'source.txt').write_text('edited')
        with self.assertRaises(RuntimeError): self.run_stage('qa')

    def test_failed_check_blocks_qa(self):
        self.run_stage('start', audit=True)
        with patch.object(w, 'execute', return_value=3):
            with self.assertRaises(RuntimeError): self.run_stage('verify')
        self.assertEqual(self.state()['verify']['verdict'], 'FAIL')
        with self.assertRaises(RuntimeError): self.run_stage('qa')

    def test_wrong_snapshot_and_blockers_reject_pass(self):
        self.prepare_audit()
        for alteration in [{'reviewed_snapshot': 'stale'}, {'blockers': ['missing tests']}]:
            result = self.result(None, None, None, None) | alteration
            with patch.object(w, 'worker', return_value=result):
                with self.assertRaises(RuntimeError): self.run_stage('qa')
            self.assertEqual(self.state()['qa']['verdict'], 'FAIL')

    def test_rerun_worker_failure_invalidates_previous_approval(self):
        self.prepare_audit()
        with patch.object(w, 'worker', side_effect=self.result): self.run_stage('qa')
        with patch.object(w, 'worker', side_effect=RuntimeError('auth denied')):
            with self.assertRaises(RuntimeError): self.run_stage('qa')
        self.assertNotIn('qa', self.state())

    def test_qa_cannot_change_source(self):
        self.prepare_audit()
        def changed(*args):
            result = self.result(*args)
            (self.root / 'source.txt').write_text('changed by QA')
            return result
        with patch.object(w, 'worker', side_effect=changed):
            with self.assertRaises(RuntimeError): self.run_stage('qa')

    def test_audit_forbids_implementation_and_commit(self):
        self.run_stage('start', audit=True)
        for stage in ['implement', 'commit']:
            with self.assertRaises(RuntimeError): self.run_stage(stage)

    def test_dirty_start_forbidden(self):
        (self.root / 'source.txt').write_text('user work')
        with self.assertRaises(RuntimeError): self.run_stage('start')

    def test_concurrent_runner_forbidden(self):
        with w.lock():
            with self.assertRaises(FileExistsError):
                with w.lock(): pass
        self.assertFalse((w.RUNS / 'workflow.lock').exists())

    def test_timeout_is_failure(self):
        code = w.execute(['python3', '-c', 'import time; time.sleep(20)'], self.root,
                         w.RUNS / 'timeout.log', 0.05)
        self.assertEqual(code, 124)

    def test_malformed_worker_report_rejected(self):
        directory = w.RUNS / 'report'; directory.mkdir()
        def fake_execute(*args):
            (directory / 'qa-result.json').write_text('{"verdict":"PASS"}')
            return 0
        with patch.object(w, 'execute', side_effect=fake_execute):
            with self.assertRaises(RuntimeError):
                w.worker('qa', directory, json.loads(w.CONFIG.read_text()), 'review')

    def test_flash_denied_action_is_not_success(self):
        directory = w.RUNS / 'flash'; directory.mkdir()
        def fake_execute(argv, cwd, log, timeout):
            log.write_text(json.dumps({'status': 'SUCCESS', 'response': 'done',
                                       'denied_actions': ['write_file']}))
            return 0
        with patch.object(w, 'execute', side_effect=fake_execute), patch.object(w, 'agy_path', return_value='agy'):
            with self.assertRaises(RuntimeError):
                w.worker('implement', directory, json.loads(w.CONFIG.read_text()), 'implement')

    def test_flash_uses_supported_edit_mode_and_local_runtime_log(self):
        directory = w.RUNS / 'flash'; directory.mkdir()
        def fake_execute(argv, cwd, log, timeout):
            self.assertEqual(argv[argv.index('--mode') + 1], 'accept-edits')
            self.assertEqual(argv[argv.index('--log-file') + 1], str(directory / 'implement-runtime.log'))
            self.assertIn('--sandbox', argv)
            log.write_text(json.dumps({'status': 'SUCCESS', 'response': 'done'}))
            return 0
        with patch.object(w, 'execute', side_effect=fake_execute), patch.object(w, 'agy_path', return_value='agy'):
            w.worker('implement', directory, json.loads(w.CONFIG.read_text()), 'implement')

    def test_missing_final_review_cannot_commit(self):
        self.run_stage('start')
        with patch.object(w, 'worker', side_effect=self.result):
            self.run_stage('implement'); self.run_stage('verify'); self.run_stage('qa')
        head = self.git('rev-parse', 'HEAD')
        with self.assertRaises(RuntimeError): self.run_stage('commit')
        self.assertEqual(head, self.git('rev-parse', 'HEAD'))

    def test_skipped_or_empty_suites_rejected_despite_zero_exit(self):
        log = w.RUNS / 'suite.log'
        for name, text in [('backend', 'Tests run: 0, Failures: 0, Errors: 0, Skipped: 0'),
                           ('backend', 'Tests run: 12, Failures: 0, Errors: 0, Skipped: 1'),
                           ('frontend-unit', 'ℹ tests 12\nℹ skipped 1'),
                           ('frontend-e2e', '12 passed\n1 skipped')]:
            log.write_text(text)
            self.assertFalse(w.test_evidence(name, log))
        log.write_text('Tests run: 34, Failures: 0, Errors: 0, Skipped: 0')
        self.assertTrue(w.test_evidence('backend', log))

    def native_fixture(self):
        directory = w.RUNS / 'native'; directory.mkdir()
        with self.assertRaises(w.NativePending):
            w.native_exchange('qa', directory, 'gpt-5.6-luna', 'Review source', {})
        request = json.loads((directory / 'qa-native-request.json').read_text())
        envelope = {key: request[key] for key in ('transport', 'stage', 'model', 'snapshot', 'nonce')}
        envelope.update(agent_task='/root/test_agent', result={'response': 'test response'})
        return directory, request, envelope

    def test_native_pending_then_authentic_envelope_contract(self):
        directory, request, envelope = self.native_fixture()
        w.save(directory / 'qa-native-result.json', envelope)
        self.assertEqual(w.native_exchange('qa', directory, 'gpt-5.6-luna', 'Review source', {}), envelope['result'])

    def test_native_wrong_model_nonce_snapshot_or_agent_rejected(self):
        directory, request, envelope = self.native_fixture()
        for key, value in [('model', 'gpt-6.2-sol'), ('nonce', 'old-nonce'), ('snapshot', 'old-code'),
                           ('stage', 'review'), ('agent_task', ''), ('result', 'PASS')]:
            w.save(directory / 'qa-native-result.json', envelope | {key: value})
            with self.assertRaises(RuntimeError):
                w.native_exchange('qa', directory, 'gpt-5.6-luna', 'Review source', {})

    def test_native_code_edit_requires_new_agent_response(self):
        directory, request, envelope = self.native_fixture()
        w.save(directory / 'qa-native-result.json', envelope)
        (self.root / 'source.txt').write_text('new source')
        with self.assertRaises(w.NativePending):
            w.native_exchange('qa', directory, 'gpt-5.6-luna', 'Review source', {})
        self.assertFalse((directory / 'qa-native-result.json').exists())
        self.assertNotEqual(request['nonce'], json.loads((directory / 'qa-native-request.json').read_text())['nonce'])

    def test_native_expired_approval_cannot_be_reused(self):
        directory, request, envelope = self.native_fixture()
        request['expires_at'] = 1
        w.save(directory / 'qa-native-request.json', request)
        w.save(directory / 'qa-native-result.json', envelope)
        with self.assertRaises(w.NativePending):
            w.native_exchange('qa', directory, 'gpt-5.6-luna', 'Review source', {})
        self.assertFalse((directory / 'qa-native-result.json').exists())

    def test_native_success_is_single_use_with_audit_history(self):
        directory, request, envelope = self.native_fixture()
        w.save(directory / 'qa-native-result.json', envelope)
        w.native_exchange('qa', directory, 'gpt-5.6-luna', 'Review source', {})
        self.assertTrue((directory / 'native-history' / ('qa-' + request['nonce'] + '.json')).exists())
        with self.assertRaises(w.NativePending):
            w.native_exchange('qa', directory, 'gpt-5.6-luna', 'Review source', {})
        self.assertNotEqual(request['nonce'], json.loads((directory / 'qa-native-request.json').read_text())['nonce'])

    def test_unlink_failure_cannot_keep_previous_approval(self):
        self.prepare_audit()
        with patch.object(w, 'worker', side_effect=self.result):
            self.run_stage('qa'); self.run_stage('review')
        with patch.object(w, 'revoke_native', side_effect=PermissionError('cannot unlink')):
            with self.assertRaises(PermissionError): self.run_stage('verify')
        self.assertNotIn('verify', self.state())
        self.assertNotIn('qa', self.state())
        self.assertNotIn('review', self.state())

    def test_native_missing_model_is_not_automatically_replaced(self):
        directory, request, envelope = self.native_fixture()
        config = json.loads(w.CONFIG.read_text())
        config['worker_transports'] = {'qa': 'codex-app'}
        with patch.object(w, 'execute') as execute:
            with self.assertRaises(w.NativePending): w.worker('qa', directory, config, 'Review source')
            execute.assert_not_called()

    def test_native_report_must_match_schema_types(self):
        result = self.result(None, None, None, None)
        for alteration in [{'scope': 12}, {'reviewed_snapshot': 12}, {'blockers': 'none'}, {'findings': [12]}]:
            with self.assertRaises(RuntimeError): w.validate_report(result | alteration)

    def test_native_qa_handoff_and_reverification_revoke_approval(self):
        config = json.loads(w.CONFIG.read_text())
        config['worker_transports'] = {'qa': 'codex-app', 'review': 'codex-app'}
        w.save(w.CONFIG, config)
        self.prepare_audit()
        with self.assertRaises(w.NativePending): self.run_stage('qa')
        self.assertNotIn('qa', self.state())
        directory = w.RUNS / 'test'
        request = json.loads((directory / 'qa-native-request.json').read_text())
        envelope = {key: request[key] for key in ('transport', 'stage', 'model', 'snapshot', 'nonce')}
        envelope.update(agent_task='/root/test_agent', result=self.result(None, None, None, None))
        w.save(directory / 'qa-native-result.json', envelope)
        self.run_stage('qa')
        self.assertEqual(self.state()['qa']['verdict'], 'PASS')
        with self.assertRaises(w.NativePending): self.run_stage('review')
        self.run_stage('verify')
        self.assertNotIn('qa', self.state())
        self.assertFalse((directory / 'qa-native-result.json').exists())
        self.assertFalse((directory / 'review-native-request.json').exists())

    def test_implement_retry_preserves_edits_after_worker_failures(self):
        self.run_stage('start')
        attempts = 0
        def failing_worker(stage, directory, config, prompt):
            nonlocal attempts
            attempts += 1
            (self.root / 'source.txt').write_text(f'edit-{attempts}\n')
            (self.root / 'untracked.txt').write_text(f'untracked-{attempts}\n')
            if attempts < 3:
                raise RuntimeError(f'worker crash {attempts}')
            return {'status': 'SUCCESS', 'response': 'done'}

        with patch.object(w, 'worker', side_effect=failing_worker):
            with self.assertRaises(RuntimeError):
                self.run_stage('implement')
            self.assertIn('implement_attempt', self.state())
            self.assertNotIn('implement', self.state())
            self.assertEqual(self.state()['implement_attempt']['version'], 1)

            with self.assertRaises(RuntimeError):
                self.run_stage('implement')
            self.assertIn('implement_attempt', self.state())
            self.assertNotIn('implement', self.state())

            self.run_stage('implement')
            self.assertEqual(self.state()['implement']['verdict'], 'PASS')
            self.assertEqual((self.root / 'source.txt').read_text(), 'edit-3\n')
            self.assertEqual((self.root / 'untracked.txt').read_text(), 'untracked-3\n')

    def test_external_dirty_first_call_and_changed_head_blocked(self):
        self.run_stage('start')
        (self.root / 'source.txt').write_text('external dirty\n')
        with patch.object(w, 'worker') as worker_mock:
            with self.assertRaises(RuntimeError):
                self.run_stage('implement')
            worker_mock.assert_not_called()
        self.assertNotIn('implement_attempt', self.state())

        (self.root / 'source.txt').write_text('original\n')
        def fail_worker(*args):
            (self.root / 'source.txt').write_text('worker edit\n')
            raise RuntimeError('worker fail')
        with patch.object(w, 'worker', side_effect=fail_worker):
            with self.assertRaises(RuntimeError):
                self.run_stage('implement')
        self.assertIn('implement_attempt', self.state())

        self.git('commit', '-am', 'new commit changing HEAD')
        with patch.object(w, 'worker') as worker_mock:
            with self.assertRaises(RuntimeError):
                self.run_stage('implement')
            worker_mock.assert_not_called()

    def test_stage_records_and_native_revoked_before_worker_and_marker_alone_blocks_downstream(self):
        self.run_stage('start')
        with patch.object(w, 'worker', side_effect=self.result):
            self.run_stage('implement'); self.run_stage('verify'); self.run_stage('qa'); self.run_stage('review')
        directory = w.RUNS / 'test'
        for stage in ('qa', 'review'):
            (directory / f'{stage}-native-request.json').write_text('{"request": 1}')
            (directory / f'{stage}-native-result.json').write_text('{"result": 1}')

        def check_revocation_worker(*args):
            cur_state = self.state()
            for s in ('implement', 'verify', 'qa', 'review'):
                self.assertNotIn(s, cur_state)
            self.assertIn('implement_attempt', cur_state)
            for s in ('qa', 'review'):
                self.assertFalse((directory / f'{s}-native-request.json').exists())
                self.assertFalse((directory / f'{s}-native-result.json').exists())
            raise RuntimeError('worker interrupt')

        with patch.object(w, 'worker', side_effect=check_revocation_worker):
            with self.assertRaises(RuntimeError):
                self.run_stage('implement')

        self.assertIn('implement_attempt', self.state())
        self.assertNotIn('implement', self.state())
        with self.assertRaises(RuntimeError):
            self.run_stage('verify')
        with self.assertRaises(RuntimeError):
            self.run_stage('commit')

    def test_legacy_pass_compatible_and_malformed_or_fail_records_rejected(self):
        self.run_stage('start')
        init_state = self.state()
        base = init_state['base']
        init_snap = init_state['initial_snapshot']
        statefile = w.RUNS / 'test/state.json'
        (self.root / 'source.txt').write_text('dirty worktree\n')

        legacy_state = {'base': base, 'audit': False, 'initial_snapshot': init_snap,
                        'implement': {'verdict': 'PASS', 'snapshot': 'a' * 64}}
        w.save(statefile, legacy_state)
        with patch.object(w, 'worker', side_effect=self.result):
            self.run_stage('implement')
        self.assertEqual(self.state()['implement']['verdict'], 'PASS')
        self.assertEqual(self.state()['implement_attempt'], {'version': 1, 'base': base, 'initial_snapshot': init_snap})

        for bad_impl in [{'verdict': 'FAIL', 'snapshot': 'a' * 64}, True, 'PASS', {'verdict': 'PASS', 'snapshot': 'bad'}]:
            w.save(statefile, {'base': base, 'audit': False, 'initial_snapshot': init_snap, 'implement': bad_impl})
            with patch.object(w, 'worker') as worker_mock:
                with self.assertRaises(RuntimeError):
                    self.run_stage('implement')
                worker_mock.assert_not_called()

        bad_markers = [
            {'version': 2, 'base': base, 'initial_snapshot': init_snap},
            {'version': True, 'base': base, 'initial_snapshot': init_snap},
            {'version': 1, 'base': 12345, 'initial_snapshot': init_snap},
            {'version': 1, 'base': 'wrong_base', 'initial_snapshot': init_snap},
            {'version': 1, 'base': base, 'initial_snapshot': 'not_sha'},
            {'version': 1, 'base': base, 'initial_snapshot': 'b' * 64},
            {'version': 1, 'base': base, 'initial_snapshot': init_snap, 'extra': True},
            'not_a_dict'
        ]
        for marker in bad_markers:
            w.save(statefile, {'base': base, 'audit': False, 'initial_snapshot': init_snap, 'implement_attempt': marker})
            with patch.object(w, 'worker') as worker_mock:
                with self.assertRaises(RuntimeError):
                    self.run_stage('implement')
                worker_mock.assert_not_called()

    def test_atomic_admission_save_and_revoke_failures(self):
        self.run_stage('start')
        statefile = w.RUNS / 'test/state.json'
        base = self.state()['base']
        init_snap = self.state()['initial_snapshot']
        (self.root / 'source.txt').write_text('dirty retry\n')

        legacy_state = {'base': base, 'audit': False, 'initial_snapshot': init_snap,
                        'implement': {'verdict': 'PASS', 'snapshot': 'a' * 64}}
        w.save(statefile, legacy_state)

        real_save = w.save
        def failing_save(path, val):
            if path == statefile and 'implement_attempt' in val and 'implement' not in val:
                raise OSError('disk write failure')
            real_save(path, val)

        with patch.object(w, 'save', side_effect=failing_save), patch.object(w, 'worker') as worker_mock:
            with self.assertRaises(OSError):
                self.run_stage('implement')
            worker_mock.assert_not_called()
        self.assertEqual(self.state(), legacy_state)

        with patch.object(w, 'revoke_native', side_effect=PermissionError('cannot revoke')), patch.object(w, 'worker') as worker_mock:
            with self.assertRaises(PermissionError):
                self.run_stage('implement')
            worker_mock.assert_not_called()

        cur_state = self.state()
        self.assertEqual(cur_state['implement_attempt'], {'version': 1, 'base': base, 'initial_snapshot': init_snap})
        for s in ('implement', 'verify', 'qa', 'review'):
            self.assertNotIn(s, cur_state)

        with patch.object(w, 'worker', side_effect=self.result):
            self.run_stage('implement')
        self.assertEqual(self.state()['implement']['verdict'], 'PASS')
        self.assertEqual(self.state()['implement_attempt'], {'version': 1, 'base': base, 'initial_snapshot': init_snap})

    def test_fake_cli_timeout_denied_malformed_success_rejected_retry_possible(self):
        self.run_stage('start')
        self.assertFalse(self.state()['audit'])
        self.assertEqual(self.state()['initial_snapshot'], w.snapshot())

        def timeout_execute(argv, cwd, log_path, timeout):
            (self.root / 'source.txt').write_text('worker edit\n')
            (self.root / 'untracked.txt').write_text('worker untracked\n')
            return 124

        with patch.object(w, 'agy_path', return_value='agy'), patch.object(w, 'execute', side_effect=timeout_execute) as mock_exec:
            with self.assertRaises(RuntimeError):
                self.run_stage('implement')
            mock_exec.assert_called_once()
        self.assertIn('implement_attempt', self.state())
        self.assertEqual(self.state()['implement_attempt']['version'], 1)
        for s in ('implement', 'verify', 'qa', 'review'):
            self.assertNotIn(s, self.state())
        self.assertEqual((self.root / 'source.txt').read_text(), 'worker edit\n')
        self.assertEqual((self.root / 'untracked.txt').read_text(), 'worker untracked\n')

        def denied_execute(argv, cwd, log_path, timeout):
            log_path.write_text(json.dumps({'status': 'SUCCESS', 'response': 'done', 'denied_actions': ['write_file']}))
            return 0
        with patch.object(w, 'agy_path', return_value='agy'), patch.object(w, 'execute', side_effect=denied_execute) as mock_denied:
            with self.assertRaises(RuntimeError):
                self.run_stage('implement')
            mock_denied.assert_called_once()
        self.assertIn('implement_attempt', self.state())
        self.assertNotIn('implement', self.state())
        for s in ('verify', 'qa', 'review'):
            self.assertNotIn(s, self.state())
        self.assertEqual((self.root / 'source.txt').read_text(), 'worker edit\n')
        self.assertEqual((self.root / 'untracked.txt').read_text(), 'worker untracked\n')

        def malformed_execute(argv, cwd, log_path, timeout):
            log_path.write_text(json.dumps({'status': 'FAILED', 'response': 'error'}))
            return 0
        with patch.object(w, 'agy_path', return_value='agy'), patch.object(w, 'execute', side_effect=malformed_execute) as mock_malformed:
            with self.assertRaises(RuntimeError):
                self.run_stage('implement')
            mock_malformed.assert_called_once()
        self.assertIn('implement_attempt', self.state())
        self.assertNotIn('implement', self.state())
        for s in ('verify', 'qa', 'review'):
            self.assertNotIn(s, self.state())
        self.assertEqual((self.root / 'source.txt').read_text(), 'worker edit\n')
        self.assertEqual((self.root / 'untracked.txt').read_text(), 'worker untracked\n')

        def success_execute(argv, cwd, log_path, timeout):
            log_path.write_text(json.dumps({'status': 'SUCCESS', 'response': 'done'}))
            return 0
        with patch.object(w, 'agy_path', return_value='agy'), patch.object(w, 'execute', side_effect=success_execute) as mock_success:
            self.run_stage('implement')
            mock_success.assert_called_once()
        self.assertEqual(self.state()['implement']['verdict'], 'PASS')
        self.assertEqual((self.root / 'source.txt').read_text(), 'worker edit\n')
        self.assertEqual((self.root / 'untracked.txt').read_text(), 'worker untracked\n')

    def test_legacy_pass_save_failure_storage_recovery_dispatches_retry(self):
        self.run_stage('start')
        statefile = w.RUNS / 'test/state.json'
        base = self.state()['base']
        init_snap = self.state()['initial_snapshot']
        (self.root / 'source.txt').write_text('dirty retry\n')

        legacy_state = {'base': base, 'audit': False, 'initial_snapshot': init_snap,
                        'implement': {'verdict': 'PASS', 'snapshot': 'a' * 64}}
        w.save(statefile, legacy_state)

        real_save = w.save
        def failing_save(path, val):
            if path == statefile and 'implement_attempt' in val and 'implement' not in val:
                raise OSError('disk write failure')
            real_save(path, val)

        with patch.object(w, 'save', side_effect=failing_save), patch.object(w, 'worker') as worker_mock:
            with self.assertRaises(OSError):
                self.run_stage('implement')
            worker_mock.assert_not_called()

        self.assertEqual(self.state(), legacy_state)
        self.assertNotIn('implement_attempt', self.state())

        with patch.object(w, 'worker', side_effect=self.result) as worker_retry:
            self.run_stage('implement')
            worker_retry.assert_called_once()

        self.assertEqual(self.state()['implement']['verdict'], 'PASS')
        self.assertEqual(self.state()['implement_attempt'], {'version': 1, 'base': base, 'initial_snapshot': init_snap})
        for s in ('verify', 'qa', 'review'):
            self.assertNotIn(s, self.state())

    def test_post_admission_revoke_failure_from_existing_valid_marker(self):
        self.run_stage('start')
        with patch.object(w, 'worker', side_effect=self.result):
            self.run_stage('implement')
            self.run_stage('verify')
            self.run_stage('qa')
            self.run_stage('review')
        marker = self.state()['implement_attempt']
        self.assertTrue(w.is_valid_marker(marker, self.state()['base'], self.state()['initial_snapshot']))
        for s in ('implement', 'verify', 'qa', 'review'):
            self.assertIn(s, self.state())

        (self.root / 'source.txt').write_text('worker edit\n')
        (self.root / 'untracked.txt').write_text('worker untracked\n')

        with patch.object(w, 'revoke_native', side_effect=PermissionError('cannot revoke')), patch.object(w, 'worker') as worker_mock:
            with self.assertRaises(PermissionError):
                self.run_stage('implement')
            worker_mock.assert_not_called()

        cur_state = self.state()
        self.assertEqual(cur_state['implement_attempt'], marker)
        for s in ('implement', 'verify', 'qa', 'review'):
            self.assertNotIn(s, cur_state)

        with patch.object(w, 'worker', side_effect=self.result) as retry_worker:
            self.run_stage('implement')
            retry_worker.assert_called_once()

        self.assertEqual(self.state()['implement']['verdict'], 'PASS')
        self.assertEqual(self.state()['implement_attempt'], marker)
        self.assertEqual((self.root / 'source.txt').read_text(), 'worker edit\n')
        self.assertEqual((self.root / 'untracked.txt').read_text(), 'worker untracked\n')


if __name__ == '__main__':
    unittest.main()
