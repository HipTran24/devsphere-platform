import { redirect } from 'next/navigation';

export const metadata = { title: "Sign-in Preview | DevSphere" };

export default function AuthPreviewPage() {
    redirect('/workspaces');
}
