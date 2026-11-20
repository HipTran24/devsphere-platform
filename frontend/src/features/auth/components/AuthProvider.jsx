"use client";

import { createContext, useContext, useEffect, useState } from 'react';
import { isAuthMock, loginAccount, loginWithGoogle } from '../api';
import { createDemoSession, restoreDemoSession, clearDemoSession } from '../demo-session';
import { clearAuthFlow } from '../demo-store';

const AuthContext = createContext(null);
export default function AuthProvider({ children }) {
    const [session, setSession] = useState(null);
    const [ready, setReady] = useState(false);
    const [notice, setNotice] = useState('');
    useEffect(() => {
        function restore() {
            const previous = restoreDemoSession();
            setSession(isAuthMock ? previous : null);
            setReady(true);
        }
        restore();
        window.addEventListener('storage', restore);
        window.addEventListener('focus', restore);
        return () => { window.removeEventListener('storage', restore); window.removeEventListener('focus', restore); };
    }, []);
    useEffect(() => {
        if (!session) return;
        const timer = setTimeout(() => {
            clearDemoSession();
            setSession(null);
            setNotice('Session expired. Sign in again.');
        }, Math.max(0, session.expiresAt - Date.now()));
        return () => clearTimeout(timer);
    }, [session]);
    function accept(result, remember) {
        if (result.mock && result.user && isAuthMock) setSession(createDemoSession(result.user, remember));
        return result;
    }
    const value = {
        session, ready, notice, setNotice,
        signIn: async (values, remember) => accept(await loginAccount(values), remember),
        signInGoogle: async remember => accept(await loginWithGoogle(), remember),
        logout: () => { clearDemoSession(); clearAuthFlow(); setSession(null); setNotice('Signed out.'); },
    };
    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
export function useAuth() { return useContext(AuthContext); }
