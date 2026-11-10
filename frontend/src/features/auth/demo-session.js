import { readDemoValue, writeDemoValue } from './demo-store.js';

const KEY = 'devsphere.demo.session';
const REVOKED = 'devsphere.demo.logout';
export function clearDemoSession() {
    writeDemoValue(KEY, null);
    writeDemoValue(KEY, null, true);
    writeDemoValue(REVOKED, Date.now(), true);
}
export function createDemoSession(user, remember = false) {
    writeDemoValue(KEY, null);
    writeDemoValue(KEY, null, true);
    const session = { user, mode: 'demo', issuedAt: Date.now(), expiresAt: Date.now() + (remember ? 14 : 1) * 86400000, remember };
    writeDemoValue(KEY, session, remember);
    return session;
}
export function restoreDemoSession(now = Date.now()) {
    const session = readDemoValue(KEY) || readDemoValue(KEY, true);
    const revokedAt = readDemoValue(REVOKED, true) || 0;
    if (!session || session.mode !== 'demo' || !session.user?.id || !Number.isFinite(session.expiresAt) || session.expiresAt <= now || session.issuedAt < revokedAt) {
        writeDemoValue(KEY, null);
        writeDemoValue(KEY, null, true);
        return null;
    }
    return session;
}
