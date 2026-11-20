import { Plus_Jakarta_Sans } from "next/font/google";
import "../styles/main.css";
import AuthProvider from "../features/auth/components/AuthProvider";

const plusJakartaSans = Plus_Jakarta_Sans({
    subsets: ["latin", "vietnamese"],
    variable: "--font-plus-jakarta-sans",
    display: "swap",
});

export const metadata = {
    title: "DevSphere",
    description: "Software project management platform",
};

export default function RootLayout({ children }) {
    return (
        <html lang="en" className={plusJakartaSans.variable}>
            <body><AuthProvider>{children}</AuthProvider></body>
        </html>
    );
}
