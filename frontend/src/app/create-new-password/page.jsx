import AuthShell from "../../features/auth/components/AuthShell";
import { CreateNewPasswordView } from "../../features/auth/components/RecoveryViews";

export const metadata = { title: "Create New Password | DevSphere" };

export default function CreateNewPasswordPage() {
    return <AuthShell mode="login"><CreateNewPasswordView /></AuthShell>;
}
