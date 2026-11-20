import test from 'node:test';
import assert from 'node:assert/strict';
import { createDemoSession, restoreDemoSession, clearDemoSession } from '../src/features/auth/demo-session.js';
import { readDemoValue } from '../src/features/auth/demo-store.js';
import { validateNewPassword, registrationErrorMessage } from '../src/features/auth/validation.js';

test('remembered demo session uses persistence, expires and is removed on logout', () => {
    const user = { id: 'demo-user', displayName: 'Demo', email: 'demo@example.com' };
    const session = createDemoSession(user, true);
    assert.equal(readDemoValue('devsphere.demo.session'), null);
    assert.deepEqual(readDemoValue('devsphere.demo.session', true), session);
    assert.deepEqual(restoreDemoSession(), session);
    assert.equal(restoreDemoSession(session.expiresAt + 1), null);
    createDemoSession(user, true);
    clearDemoSession();
    assert.equal(restoreDemoSession(), null);
});
test('unremembered demo session uses tab storage and does not store credentials', () => {
    const user = { id: 'demo', displayName: 'Demo' };
    const session = createDemoSession(user, false);
    assert.deepEqual(readDemoValue('devsphere.demo.session'), session);
    assert.equal(readDemoValue('devsphere.demo.session', true), null);
    const serialized = JSON.stringify(session);
    assert.equal(/password|token|otp/i.test(serialized), false);
    clearDemoSession();
});
test('new password validates required inputs, length and confirmation', () => {
    for (const [password, confirmPassword, expected] of [
        ['', '', 'Complete all required fields.'],
        ['valid-password', '', 'Confirm your password.'],
        ['short', 'short', 'Use at least 12 characters.'],
        ['valid-password', 'different-password', 'Passwords don’t match.'],
        ['valid-password', 'valid-password', ''],
    ]) assert.equal(registrationErrorMessage(validateNewPassword({ password, confirmPassword })), expected);
});
