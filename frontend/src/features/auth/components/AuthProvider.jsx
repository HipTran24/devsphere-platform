"use client";

import { createContext, useContext, useEffect, useState, useCallback } from 'react';
import { isAuthMock, loginAccount, loginWithGoogle, logoutAccount } from '../api';
import { createDemoSession, restoreDemoSession, clearDemoSession } from '../demo-session';
import { clearAuthFlow } from '../demo-store';
import {
    setAccessToken,
    clearAccessToken,
    refreshSession,
    fetchCurrentUser,
    onAuthFailure,
} from '../../../lib/api-client';

const AuthContext = createContext(null);

export default function AuthProvider({ children }) {
    const [session, setSession] = useState(null);
    const [ready, setReady] = useState(false);
    const [notice, setNotice] = useState('');

    const handleSignOut = useCallback(async () => {
        try {
            await logoutAccount();
        } catch {
            clearAccessToken();
        }
        clearDemoSession();
        clearAuthFlow();
        setSession(null);
        setNotice('Signed out.');
    }, []);

    useEffect(() => {
        let isMounted = true;

        async function restore() {
            if (isAuthMock) {
                const previous = restoreDemoSession();
                if (isMounted) {
                    setSession(previous);
                    setReady(true);
                }
                return;
            }

            // Real backend session hydration via silent refresh
            try {
                const refreshed = await refreshSession();
                if (refreshed?.accessToken) {
                    const me = await fetchCurrentUser();
                    if (isMounted && me?.userId) {
                        setSession({
                            user: {
                                id: me.userId,
                                displayName: me.displayName || 'DevSphere User',
                                systemRole: me.systemRole || 'USER',
                            },
                            token: refreshed.accessToken,
                            accessToken: refreshed.accessToken,
                            expiresAt: refreshed.expiresAt,
                            isReal: true,
                        });
                    }
                }
            } catch {
                if (isMounted) setSession(null);
            } finally {
                if (isMounted) setReady(true);
            }
        }

        restore();

        const handleStorageOrFocus = () => {
            if (isAuthMock) {
                const prev = restoreDemoSession();
                setSession(prev);
            }
        };

        window.addEventListener('storage', handleStorageOrFocus);
        window.addEventListener('focus', handleStorageOrFocus);

        const unsubscribeAuthFailure = onAuthFailure(() => {
            if (isMounted) {
                clearDemoSession();
                clearAuthFlow();
                setSession(null);
                setNotice('Session expired. Sign in again.');
            }
        });

        return () => {
            isMounted = false;
            window.removeEventListener('storage', handleStorageOrFocus);
            window.removeEventListener('focus', handleStorageOrFocus);
            unsubscribeAuthFailure();
        };
    }, []);

    useEffect(() => {
        if (!session?.expiresAt) return;
        const remaining = Math.max(0, new Date(session.expiresAt).getTime() - Date.now());
        const timer = setTimeout(() => {
            if (session.isReal) {
                // In real mode, attempt refresh before dropping session
                refreshSession()
                    .then((refreshed) => {
                        setSession((prev) =>
                            prev ? { ...prev, token: refreshed.accessToken, expiresAt: refreshed.expiresAt } : null
                        );
                    })
                    .catch(() => {
                        clearAccessToken();
                        setSession(null);
                        setNotice('Session expired. Sign in again.');
                    });
            } else {
                clearDemoSession();
                setSession(null);
                setNotice('Session expired. Sign in again.');
            }
        }, remaining);
        return () => clearTimeout(timer);
    }, [session]);

    const signIn = useCallback(async (values, remember) => {
        const result = await loginAccount(values);
        if (result.mock && result.user && isAuthMock) {
            const demoSession = createDemoSession(result.user, remember);
            setSession(demoSession);
            return result;
        }
        if (result.accessToken && result.user) {
            setAccessToken(result.accessToken, result.expiresAt);
            const realSession = {
                user: result.user,
                token: result.accessToken,
                accessToken: result.accessToken,
                expiresAt: result.expiresAt ? new Date(result.expiresAt).getTime() : Date.now() + 900000,
                isReal: true,
            };
            setSession(realSession);
            return result;
        }
        return result;
    }, []);

    const signInGoogle = useCallback(async (remember) => {
        const result = await loginWithGoogle();
        if (result.mock && result.user && isAuthMock) {
            const demoSession = createDemoSession(result.user, remember);
            setSession(demoSession);
        }
        return result;
    }, []);

    const value = {
        session,
        ready,
        notice,
        setNotice,
        signIn,
        signInGoogle,
        logout: handleSignOut,
    };

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
    return useContext(AuthContext);
}
