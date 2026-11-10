// Display-only demo state. Never store passwords, OTPs, or real access tokens here.
const memory = new Map();
export function readDemoValue(key, persistent = false) {
    try {
        const storage = persistent ? globalThis.localStorage : globalThis.sessionStorage;
        const raw = storage ? storage.getItem(key) : memory.get(`${persistent ? 'local' : 'tab'}:${key}`);
        return raw ? JSON.parse(raw) : null;
    } catch { return null; }
}
export function writeDemoValue(key, value, persistent = false) {
    const serialized = value === null ? null : JSON.stringify(value);
    try {
        const storage = persistent ? globalThis.localStorage : globalThis.sessionStorage;
        if (storage) {
            if (serialized === null) storage.removeItem(key);
            else storage.setItem(key, serialized);
        } else if (serialized === null) memory.delete(`${persistent ? 'local' : 'tab'}:${key}`);
        else memory.set(`${persistent ? 'local' : 'tab'}:${key}`, serialized);
    } catch { /* Private browsing/storage restrictions must not crash the form. */ }
}

const FLOW_KEY = 'devsphere.auth.flow';
export function saveAuthFlow(flow) { writeDemoValue(FLOW_KEY, flow); }
export function getAuthFlow() { return readDemoValue(FLOW_KEY); }
export function clearAuthFlow() { writeDemoValue(FLOW_KEY, null); }
