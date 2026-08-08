"use client";

import { startTransition, useState } from "react";
import { useRouter } from "next/navigation";
import FluentConsolePreview from "@/app/components/fluent-console-preview";
import {
  COOKIE_DAYS,
  COOKIE_NAME,
  setCookieValue,
} from "@/src/utils/cookie";

const DEMO_USERNAME = "admin";
const DEMO_PASSWORD = "admin";

export default function LoginClient() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [message, setMessage] = useState("");
  const [messageType, setMessageType] = useState<"info" | "error">("info");

  function setStatus(nextMessage: string, type: "info" | "error" = "info") {
    setMessage(nextMessage);
    setMessageType(type);
  }

  function handleFillDemo() {
    setUsername(DEMO_USERNAME);
    setPassword(DEMO_PASSWORD);
    setStatus("已填充演示账号。");
  }

  function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setStatus("");

    const trimmedUsername = username.trim();
    if (!trimmedUsername || !password) {
      setStatus("请输入账号与密码。", "error");
      return;
    }

    if (trimmedUsername !== DEMO_USERNAME || password !== DEMO_PASSWORD) {
      setStatus("账号或密码错误（演示账号：admin / admin）。", "error");
      return;
    }

    setCookieValue(
      COOKIE_NAME,
      JSON.stringify({ user: trimmedUsername, ts: Date.now() }),
      COOKIE_DAYS,
    );
    setStatus("登录成功，正在跳转…");

    startTransition(() => {
      router.replace("/chat-stream");
    });
  }

  return (
    <main className="relative flex min-h-screen items-center justify-center overflow-hidden px-5 py-8 md:px-8">
      <div className="absolute left-[6%] top-14 h-48 w-48 rounded-full bg-[radial-gradient(circle,rgba(149,194,255,0.52),rgba(149,194,255,0))]" />
      <div className="absolute bottom-10 right-[10%] h-60 w-60 rounded-full bg-[radial-gradient(circle,rgba(199,224,255,0.9),rgba(199,224,255,0))]" />

      <div className="flex w-full justify-center">

        <section className="glass-panel hover-lift flex w-full max-w-[620px] overflow-hidden rounded-[34px]">
          <div className="relative flex w-full flex-col justify-center gap-5 px-6 py-7 md:px-8">
            <div className="absolute right-0 top-0 h-28 w-28 rounded-full bg-[radial-gradient(circle,rgba(171,205,255,0.42),rgba(171,205,255,0))]" />
            <div className="surface-panel fluent-accent-border relative rounded-[30px] p-6 md:p-7">
              <div className="mb-5">
                <p className="text-[11px] font-semibold uppercase tracking-[0.24em] text-[#5a7db0]">
                  Sign In
                </p>
                <h2 className="mt-3 text-[28px] font-semibold tracking-tight text-[#18304d]">
                  登录工作台
                </h2>
                <p className="mt-2 text-sm leading-7 text-[var(--muted-soft)]">
                  演示账号：admin / admin。后续可替换为真实鉴权接口或接入企业统一登录。
                </p>
              </div>

              <div className="mb-5 flex items-center gap-3 rounded-[24px] border border-white/80 bg-[linear-gradient(180deg,rgba(244,248,255,0.92),rgba(234,242,255,0.82))] px-4 py-3 shadow-[inset_0_1px_0_rgba(255,255,255,0.96)]">
                <div className="flex h-11 w-11 items-center justify-center rounded-[18px] bg-[linear-gradient(180deg,#ffffff,#dbeaff)] text-xs font-bold text-[#0f6cfd]">
                  ID
                </div>
                <div>
                  <div className="text-sm font-semibold text-[#26466d]">
                    Windows 风格安全入口
                  </div>
                  <div className="text-xs text-[var(--muted-soft)]">
                    轻盈层次、柔和边缘与系统级按钮反馈
                  </div>
                </div>
              </div>

              <form className="space-y-4" onSubmit={handleSubmit} autoComplete="on">
                <div className="space-y-2">
                  <label htmlFor="username" className="text-xs font-medium text-[#4e6686]">
                    账号
                  </label>
                  <input
                    id="username"
                    name="username"
                    type="text"
                    value={username}
                    onChange={(event) => setUsername(event.target.value)}
                    placeholder="请输入账号"
                    autoComplete="username"
                    className="ring-focus fluent-field w-full rounded-[20px] px-4 py-3"
                  />
                </div>

                <div className="space-y-2">
                  <label htmlFor="password" className="text-xs font-medium text-[#4e6686]">
                    密码
                  </label>
                  <input
                    id="password"
                    name="password"
                    type="password"
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                    placeholder="请输入密码"
                    autoComplete="current-password"
                    className="ring-focus fluent-field w-full rounded-[20px] px-4 py-3"
                  />
                </div>

                <div className="flex flex-col gap-3 pt-1 sm:flex-row sm:items-center sm:justify-between">
                  <button
                    type="submit"
                    className="hover-lift fluent-primary cursor-pointer rounded-[20px] px-5 py-3 font-semibold"
                  >
                    登录并进入控制台
                  </button>
                  <button
                    type="button"
                    onClick={handleFillDemo}
                    className="hover-lift fluent-secondary cursor-pointer rounded-[20px] px-5 py-3 font-semibold"
                  >
                    填充演示账号
                  </button>
                </div>

                <p
                  className={`min-h-5 text-xs leading-6 ${
                    messageType === "error"
                      ? "text-[var(--danger)]"
                      : "text-[var(--muted-soft)]"
                  }`}
                >
                  {message}
                </p>
              </form>
            </div>

            <div className="grid gap-3 sm:grid-cols-3">
              {[
                ["Hover", "180ms"],
                ["反馈", "System Smooth"],
                ["材质", "Acrylic"],
              ].map(([label, value]) => (
                <div
                  key={label}
                  className="surface-panel rounded-[24px] border border-white/82 px-4 py-3"
                >
                  <div className="text-[11px] uppercase tracking-[0.16em] text-[#6c84a2]">
                    {label}
                  </div>
                  <div className="mt-1 text-sm font-semibold text-[#1d3658]">
                    {value}
                  </div>
                </div>
              ))}
            </div>

            <p className="text-center text-xs text-[#7a89a0]">
              © AI Agent Scaffold · Fluent 2 inspired Web workspace
            </p>
          </div>
        </section>
      </div>
    </main>
  );
}
