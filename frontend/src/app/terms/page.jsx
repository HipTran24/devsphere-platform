import Link from "next/link";
import AuthFlowShell from "../../features/auth/components/AuthFlowShell";
import styles from "../../features/auth/styles/AuthForm.module.css";

export const metadata = { title: "Terms Availability | DevSphere" };

export default function TermsPage() {
    return <AuthFlowShell>
        <h1 className={styles.title}>Terms of Service</h1>
        <p className={styles.notice}>Pending publication</p>
        <p className={styles.hint}>The DevSphere team has not published approved Terms of Service yet. The current registration form is a frontend demonstration and creates no real account.</p>
        <p className={styles.hint}>Use fictional information while testing. Demo verification, password reset and Google sign-in do not create real accounts or change credentials. Approved terms are required before real registration is enabled.</p>
        <Link className={styles.primary} href="/register">Back to Create Account</Link>
    </AuthFlowShell>;
}
