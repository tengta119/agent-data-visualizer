import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "AI 智能体工作台",
  description: "Data Visualizer 前端智能体登录与对话工作台",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN" className="h-full antialiased">
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}
