import AuthShell from "../../features/auth/components/AuthShell";
import { CheckEmailView } from "../../features/auth/components/RecoveryViews";

export const metadata = { title: "Check Your Email | DevSphere" };

export default function CheckEmailPage() {
    return <AuthShell mode="login"><CheckEmailView /></AuthShell>;
}
