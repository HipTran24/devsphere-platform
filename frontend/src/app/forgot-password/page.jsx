import AuthShell from "../../features/auth/components/AuthShell";
import { ForgotPasswordView } from "../../features/auth/components/RecoveryViews";

export const metadata = { title: "Forgot Password | DevSphere" };

export default function ForgotPasswordPage() {
    return <AuthShell mode="login"><ForgotPasswordView /></AuthShell>;
}
