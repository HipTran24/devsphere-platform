import { readDemoValue, writeDemoValue } from './demo-store.js';

// This adapter deliberately never grants a real session. Production disables it.
export const isAuthMock = process.env.NODE_ENV === 'development' && process.env.NEXT_PUBLIC_AUTH_MOCK !== 'false';
export const MOCK_OTP = '123456';
const CHALLENGES_KEY = 'devsphere.demo.challenges';
const GRANTS_KEY = 'devsphere.demo.reset-grants';
export class AuthError extends Error {
    constructor(code, message, field) { super(message); this.code = code; this.field = field; }
}
async function simulate(email = '') {
    if (!isAuthMock) throw new AuthError('AUTH_NOT_CONFIGURED', 'Authentication is not connected yet.');
    await new Promise(resolve => setTimeout(resolve, 500));
    const scenario = email.toLowerCase().split('@')[0];
    if (scenario === 'network') throw new AuthError('NETWORK_ERROR', 'Connection interrupted. Please try again.');
    if (scenario === 'server') throw new AuthError('SERVER_ERROR', 'Service unavailable. Please try again.');
    return scenario;
}
function records(key) { return readDemoValue(key) || {}; }
function createChallenge(email, purpose) {
    const challenge = { id: crypto.randomUUID(), email, purpose, expiresAt: Date.now() + 600000, resendAt: Date.now() + 30000, attempts: 0 };
    const all = records(CHALLENGES_KEY);
    for (const [id, item] of Object.entries(all)) if (item.expiresAt < Date.now() - 600000) delete all[id];
    all[challenge.id] = challenge;
    writeDemoValue(CHALLENGES_KEY, all);
    return challenge;
}
function requireChallenge(id, purpose) {
    const challenge = records(CHALLENGES_KEY)[id];
    if (!challenge || (purpose && challenge.purpose !== purpose)) throw new AuthError('OTP_EXPIRED', 'Request a new code.');
    return challenge;
}
function checkCode(id, code, purpose) {
    const challenge = requireChallenge(id, purpose);
    if (Date.now() >= challenge.expiresAt) throw new AuthError('OTP_EXPIRED', 'Code expired. Resend a new code.');
    if (challenge.attempts >= 5) throw new AuthError('OTP_LIMITED', 'Too many attempts. Resend a new code.');
    const all = records(CHALLENGES_KEY);
    challenge.attempts += 1;
    all[id] = challenge;
    writeDemoValue(CHALLENGES_KEY, all);
    if (code !== MOCK_OTP) throw new AuthError('OTP_INVALID', 'Incorrect code. Try again.', 'otp');
    delete all[id];
    writeDemoValue(CHALLENGES_KEY, all);
    return challenge;
}
export async function registerAccount({ displayName, email }) {
    if (await simulate(email) === 'existing') throw new AuthError('EMAIL_EXISTS', 'Email already registered.', 'email');
    return { ...createChallenge(email, 'VERIFY_EMAIL'), displayName };
}
export async function loginAccount({ email }) {
    const scenario = await simulate(email);
    if (scenario === 'invalid') throw new AuthError('AUTH_INVALID', 'Email or password is incorrect.');
    if (scenario === 'locked') throw new AuthError('ACCOUNT_LOCKED', 'Account locked. Contact your administrator.');
    if (scenario === 'limited') throw new AuthError('RATE_LIMITED', 'Too many attempts. Please try later.');
    if (scenario === 'unverified') return { verificationRequired: true, challenge: createChallenge(email, 'VERIFY_EMAIL') };
    return { mock: true, user: { id: 'demo-user', displayName: email.split('@')[0], email, provider: 'email' } };
}
export async function loginWithGoogle() {
    await simulate();
    return { mock: true, user: { id: 'demo-google', displayName: 'Google Demo', email: 'google-demo@example.com', provider: 'google-demo' } };
}
export async function requestPasswordReset({ email }) { await simulate(email); return createChallenge(email, 'RESET_PASSWORD'); }
export async function resendCode(id) {
    await simulate();
    const challenge = requireChallenge(id);
    if (Date.now() < challenge.resendAt) throw new AuthError('RATE_LIMITED', 'Please wait before resending.');
    Object.assign(challenge, { expiresAt: Date.now() + 600000, resendAt: Date.now() + 30000, attempts: 0 });
    writeDemoValue(CHALLENGES_KEY, { ...records(CHALLENGES_KEY), [id]: challenge });
    return challenge;
}
export async function verifyEmail({ challengeId, code }) { await simulate(); checkCode(challengeId, code, 'VERIFY_EMAIL'); return { mock: true }; }
export async function verifyResetCode({ challengeId, code }) {
    await simulate();
    checkCode(challengeId, code, 'RESET_PASSWORD');
    const grant = { id: crypto.randomUUID(), expiresAt: Date.now() + 600000, mock: true };
    writeDemoValue(GRANTS_KEY, { ...records(GRANTS_KEY), [grant.id]: grant });
    return grant;
}
export async function resetPassword({ resetGrantId }) {
    await simulate();
    const grants = records(GRANTS_KEY);
    const grant = grants[resetGrantId];
    if (!grant || Date.now() >= grant.expiresAt) throw new AuthError('RESET_EXPIRED', 'Reset expired. Request a new code.');
    delete grants[resetGrantId];
    writeDemoValue(GRANTS_KEY, grants);
    return { mock: true };
}
