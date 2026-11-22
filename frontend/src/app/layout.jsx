import { Inter } from "next/font/google";
import "../styles/main.css";
import AuthProvider from "../features/auth/components/AuthProvider";

const inter = Inter({
    subsets: ["latin", "vietnamese"],
    variable: "--font-inter",
    display: "swap",
});

export const metadata = {
    title: "DevSphere",
    description: "Software project management platform",
};

export default function RootLayout({ children }) {
    return (
        <html lang="en" className={inter.variable}>
            <body><AuthProvider>{children}</AuthProvider></body>
        </html>
    );
}
