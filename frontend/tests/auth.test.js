import test from "node:test";
import assert from "node:assert/strict";
import { registrationErrorMessage, validateEmail, validateLogin, validateRegistration } from "../src/features/auth/validation.js";

test("registration summary prioritizes missing inputs before invalid values and terms", () => {
    const values = { displayName: "", email: "", password: "short", confirmPassword: "other" };
    assert.equal(registrationErrorMessage(validateRegistration(values)), "Complete all required fields.");
    values.email = "invalid";
    assert.equal(registrationErrorMessage(validateRegistration(values)), "Enter your full name.");
    values.displayName = "Demo";
    assert.equal(registrationErrorMessage(validateRegistration(values)), "Enter a valid email.");
    values.email = "demo@example.com";
    assert.equal(registrationErrorMessage(validateRegistration(values)), "Use at least 12 characters.");
    values.password = "demo-password-123";
    assert.equal(registrationErrorMessage(validateRegistration(values)), "Passwords don’t match.");
    values.confirmPassword = values.password;
    assert.equal(registrationErrorMessage(validateRegistration(values)), "Accept the terms to continue.");
    values.terms = "on";
    assert.equal(registrationErrorMessage(validateRegistration(values)), "");
});

test("registration rejects whitespace name, bad email, short/mismatched passwords and missing acknowledgement", () => {
    const errors = validateRegistration({ displayName: "   ", email: "bad", password: "short", confirmPassword: "other" });
    assert.deepEqual(Object.keys(errors), ["fullName", "email", "password", "confirmPassword", "terms"]);
    assert.deepEqual(validateRegistration({ displayName: " Demo ", email: " demo@example.com ", password: " 1234567890 ", confirmPassword: " 1234567890 ", terms: "on" }), {});
});

test("login does not apply registration password length rules", () => {
    assert.deepEqual(validateLogin({ email: "demo@example.com", password: "short" }), {});
    assert.ok(validateLogin({ email: "", password: "" }).password);
    assert.ok(validateEmail("bad@example.com extra"));
});

test("mock adapter handles errors, OTP expiry/replay, reset purpose and production gating", async t => {
    const originalEnvironment = process.env.NODE_ENV;
    const originalMockFlag = process.env.NEXT_PUBLIC_AUTH_MOCK;
    const realNow = Date.now;
    process.env.NODE_ENV = "development";
    delete process.env.NEXT_PUBLIC_AUTH_MOCK;
    const api = await import("../src/features/auth/api.js?test=development");
    const rejectsCode = (promise, code) => assert.rejects(promise, error => error.code === code);
    try {
        await t.test("email duplicate and login scenarios", async () => {
            await rejectsCode(api.registerAccount({ email: "existing@example.com" }), "EMAIL_EXISTS");
            for (const [email, code] of [["invalid", "AUTH_INVALID"], ["locked", "ACCOUNT_LOCKED"], ["limited", "RATE_LIMITED"], ["network", "NETWORK_ERROR"], ["server", "SERVER_ERROR"]]) {
                await rejectsCode(api.loginAccount({ email: `${email}@example.com`, password: "fictional" }), code);
            }
            const result = await api.loginAccount({ email: "demo@example.com", password: "fictional" });
            assert.equal(result.mock, true);
            assert.equal(result.user.email, 'demo@example.com');
            assert.equal(result.accessToken, undefined);
            assert.equal((await api.loginAccount({ email: "unverified@example.com" })).verificationRequired, true);
        });
        await t.test("wrong code, resend cooldown and one-time verification", async () => {
            const challenge = await api.registerAccount({ email: "demo@example.com" });
            await rejectsCode(api.verifyEmail({ challengeId: challenge.id, code: "000000" }), "OTP_INVALID");
            await rejectsCode(api.resendCode(challenge.id), "RATE_LIMITED");
            await api.verifyEmail({ challengeId: challenge.id, code: api.MOCK_OTP });
            await rejectsCode(api.verifyEmail({ challengeId: challenge.id, code: api.MOCK_OTP }), "OTP_EXPIRED");
        });
        await t.test("expired code can be renewed after cooldown", async () => {
            const challenge = await api.registerAccount({ email: "demo@example.com" });
            Date.now = () => challenge.expiresAt + 1;
            await rejectsCode(api.verifyEmail({ challengeId: challenge.id, code: api.MOCK_OTP }), "OTP_EXPIRED");
            const renewed = await api.resendCode(challenge.id);
            assert.ok(renewed.expiresAt > Date.now());
            await api.verifyEmail({ challengeId: challenge.id, code: api.MOCK_OTP });
            Date.now = realNow;
        });
        await t.test("reset code cannot activate an account", async () => {
            const challenge = await api.requestPasswordReset({ email: "demo@example.com" });
            await rejectsCode(api.verifyEmail({ challengeId: challenge.id, code: api.MOCK_OTP }), "OTP_EXPIRED");
            const grant = await api.verifyResetCode({ challengeId: challenge.id, code: api.MOCK_OTP });
            await api.resetPassword({ resetGrantId: grant.id, password: "fictional password" });
            await rejectsCode(api.resetPassword({ resetGrantId: grant.id }), "RESET_EXPIRED");
        });
        await t.test("production and explicit opt-out never grant mock success", async () => {
            process.env.NODE_ENV = "production";
            const production = await import("../src/features/auth/api.js?test=production");
            assert.equal(production.isAuthMock, false);
            await rejectsCode(production.loginAccount({ email: "demo@example.com" }), "AUTH_NOT_CONFIGURED");
            process.env.NODE_ENV = "development";
            process.env.NEXT_PUBLIC_AUTH_MOCK = "false";
            const disabled = await import("../src/features/auth/api.js?test=disabled");
            await rejectsCode(disabled.registerAccount({ email: "demo@example.com" }), "AUTH_NOT_CONFIGURED");
        });
    } finally {
        Date.now = realNow;
        if (originalEnvironment === undefined) delete process.env.NODE_ENV;
        else process.env.NODE_ENV = originalEnvironment;
        if (originalMockFlag === undefined) delete process.env.NEXT_PUBLIC_AUTH_MOCK;
        else process.env.NEXT_PUBLIC_AUTH_MOCK = originalMockFlag;
    }
});
