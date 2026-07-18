"use client";

import { startTransition, useMemo, useRef, useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import AgentDrawIoPanel from "@/app/components/agent-drawio-panel";
import {
  ensureSuccess,
  isBackendUnavailableError,
  requestJson,
  type AgentConfig,
} from "@/lib/agent-api";
import { COOKIE_NAME, deleteCookieValue, formatTime } from "@/lib/agent-auth";

type ChatBubble = {
  id: string;
  side: "user" | "agent";
  text: string;
  meta?: string;
};

type ChatClientProps = {
  apiBase: string;
  initialAgents: AgentConfig[];
  initialBackendIssue: string | null;
  userId: string;
  loginTs: number | null;
};

function createBubble(
  side: ChatBubble["side"],
  text: string,
  meta?: string,
): ChatBubble {
  return {
    id: `${side}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    side,
    text,
    meta,
  };
}

export default function ChatClient({
  apiBase,
  initialAgents,
  initialBackendIssue,
  userId,
  loginTs,
}: ChatClientProps) {
  const router = useRouter();
  const chatRef = useRef<HTMLDivElement | null>(null);
  const [agents, setAgents] = useState(initialAgents);
  const [selectedAgentId, setSelectedAgentId] = useState(
    initialAgents[0]?.agentId ?? "",
  );
  const [sessionId, setSessionId] = useState("");
  const [message, setMessage] = useState("");
  const [status, setStatus] = useState(
    initialBackendIssue ? `智能体列表加载失败：${initialBackendIssue}` : "已加载智能体列表。",
  );
  const [statusType, setStatusType] = useState<"info" | "error">(
    initialBackendIssue ? "error" : "info",
  );
  const [sending, setSending] = useState(false);
  const [bubbles, setBubbles] = useState<ChatBubble[]>([]);
  const [backendIssue, setBackendIssue] = useState<string | null>(
    initialBackendIssue,
  );

  const selectedAgent = useMemo(
    () => agents.find((agent) => agent.agentId === selectedAgentId) ?? null,
    [agents, selectedAgentId],
  );

  useEffect(() => {
    const node = chatRef.current;
    if (!node) return;
    node.scrollTop = node.scrollHeight;
  }, [bubbles]);

  function setStatusMessage(
    nextStatus: string,
    type: "info" | "error" = "info",
  ) {
    setStatus(nextStatus);
    setStatusType(type);
  }

  async function loadAgents() {
    try {
      const response = await requestJson<AgentConfig[]>(
        "/api/v1/query_ai_agent_config_list",
        { method: "GET" },
      );
      const data = ensureSuccess(response) ?? [];
      setAgents(data);
      setSelectedAgentId((current) =>
        data.some((agent) => agent.agentId === current)
          ? current
          : (data[0]?.agentId ?? ""),
      );
      setBackendIssue(null);
      setStatusMessage("已加载智能体列表。");
    } catch (error) {
      const messageText =
        error instanceof Error ? error.message : "智能体列表加载失败";
      setAgents([]);
      setSelectedAgentId("");
      setStatusMessage(`智能体列表加载失败：${messageText}`, "error");
      if (isBackendUnavailableError(error)) {
        setBackendIssue(messageText);
      }
    }
  }

  async function createSession(agentId: string) {
    const response = await requestJson<{ sessionId: string }>(
      "/api/v1/create_session",
      {
        method: "POST",
        body: JSON.stringify({ agentId, userId }),
      },
    );
    const data = ensureSuccess(response);
    const nextSessionId = data?.sessionId?.trim();
    if (!nextSessionId) {
      throw new Error("创建会话失败：未返回 sessionId");
    }
    setSessionId(nextSessionId);
    return nextSessionId;
  }

  async function sendChat(agentId: string, nextSessionId: string, content: string) {
    const response = await requestJson<{ content: string }>("/api/v1/chat", {
      method: "POST",
      body: JSON.stringify({
        agentId,
        userId,
        sessionId: nextSessionId,
        message: content,
      }),
    });
    return ensureSuccess(response)?.content ?? "";
  }

  function updateBubble(id: string, nextText: string, nextMeta?: string) {
    setBubbles((current) =>
      current.map((bubble) =>
        bubble.id === id
          ? { ...bubble, text: nextText, meta: nextMeta ?? bubble.meta }
          : bubble,
      ),
    );
  }

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setStatusMessage("");

    const trimmedMessage = message.trim();
    if (!selectedAgentId) {
      setStatusMessage("请先选择智能体。", "error");
      return;
    }
    if (!trimmedMessage) return;

    const userBubble = createBubble("user", trimmedMessage, `userId=${userId}`);
    const agentBubble = createBubble("agent", "思考中…", `agentId=${selectedAgentId}`);
    setBubbles((current) => [...current, userBubble, agentBubble]);
    setMessage("");
    setSending(true);

    try {
      const nextSessionId = await createSession(selectedAgentId);
      updateBubble(
        agentBubble.id,
        "思考中…",
        `agentId=${selectedAgentId} · sessionId=${nextSessionId}`,
      );
      const reply = await sendChat(selectedAgentId, nextSessionId, trimmedMessage);
      updateBubble(
        agentBubble.id,
        reply || "(空响应)",
        `agentId=${selectedAgentId} · sessionId=${nextSessionId}`,
      );
      setBackendIssue(null);
      setStatusMessage("已完成。");
    } catch (error) {
      const messageText = error instanceof Error ? error.message : "请求失败";
      updateBubble(
        agentBubble.id,
        `请求失败：${messageText}`,
        "请检查 API_BASE / 服务端 CORS / 接口是否启动",
      );
      setStatusMessage(`失败：${messageText}`, "error");
      if (isBackendUnavailableError(error)) {
        setBackendIssue(messageText);
      }
    } finally {
      setSending(false);
    }
  }

  function handleLogout() {
    deleteCookieValue(COOKIE_NAME);
    startTransition(() => {
      router.replace("/login");
    });
  }

  return (
    <div className="relative flex h-screen w-full flex-col overflow-hidden">
      <div className="pointer-events-none absolute left-[8%] top-6 h-48 w-48 rounded-full bg-[radial-gradient(circle,rgba(148,192,255,0.48),rgba(148,192,255,0))]" />
      <div className="pointer-events-none absolute bottom-8 right-[6%] h-64 w-64 rounded-full bg-[radial-gradient(circle,rgba(193,221,255,0.78),rgba(193,221,255,0))]" />

      <header className="sticky top-0 z-10 border-b border-white/58 bg-[rgba(247,250,255,0.62)] backdrop-blur-[26px]">
        <div className="flex w-full flex-wrap items-start justify-between gap-x-4 gap-y-3 px-3 py-3.5 md:px-4 md:py-4">
          <div className="flex min-w-0 items-start gap-3">
            <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-[18px] bg-[linear-gradient(180deg,#ffffff,#dceafe)] text-sm font-bold tracking-[0.08em] text-[#0f6cfd] shadow-[0_10px_24px_rgba(107,140,194,0.16)]">
              DV
            </div>
            <div className="min-w-0 pt-0.5">
              <strong className="block truncate text-sm leading-5 text-[#1a3455]">
                AI 智能体对话 By Ai Agent Scaffold - @小傅哥
              </strong>
              <span className="mt-0.5 block truncate text-xs leading-5 text-[var(--muted-soft)]">
                API: {apiBase}
              </span>
            </div>
          </div>

          <div className="flex flex-wrap items-start justify-end gap-2">
            <select
              value={selectedAgentId}
              onChange={(event) => {
                setSelectedAgentId(event.target.value);
              }}
              className="ring-focus fluent-field h-[56px] min-w-[220px] rounded-[18px] px-4 text-sm"
              aria-label="选择智能体"
            >
              <option value="">
                {agents.length ? "请选择智能体" : "加载中或暂无数据"}
              </option>
              {agents.map((agent) => (
                <option key={agent.agentId} value={agent.agentId}>
                  {agent.agentName || agent.agentId}
                  {agent.agentDesc ? ` - ${agent.agentDesc}` : ""}
                </option>
              ))}
            </select>

            <div className="surface-panel flex h-[56px] min-w-[210px] flex-col justify-center rounded-[18px] border border-white/82 px-4">
              <b className="block text-xs leading-4 text-[#213c61]">{userId}</b>
              <span className="mt-0.5 block text-xs leading-4 text-[var(--muted-soft)]">
                {loginTs ? `登录于 ${formatTime(loginTs)}` : "已登录"}
              </span>
            </div>

            <button
              type="button"
              onClick={handleLogout}
              className="hover-lift fluent-secondary flex h-[56px] items-center justify-center self-start rounded-[18px] px-6 text-sm font-semibold"
            >
              退出
            </button>
          </div>
        </div>
      </header>

      <main className="flex min-h-0 flex-1 overflow-hidden px-2 py-3 md:px-3 md:py-4">
        <div className="grid h-full min-h-0 w-full min-w-0 gap-3 md:gap-4 lg:grid-cols-[3fr_1fr]">
          <AgentDrawIoPanel
            userId={userId}
            sessionId={sessionId}
            agentLabel={
              selectedAgent
                ? `${selectedAgent.agentName || selectedAgent.agentId}${
                    selectedAgent.agentDesc ? ` - ${selectedAgent.agentDesc}` : ""
                  }`
                : "-"
            }
          />

          <section className="glass-panel flex min-h-0 min-w-0 flex-col overflow-hidden rounded-[34px]">
            <div className="border-b border-[var(--line)] px-5 py-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <h1 className="text-lg font-semibold tracking-tight text-[#17304d]">
                    智能体控制中枢
                  </h1>
                  <p className="mt-1 text-sm text-[var(--muted-soft)]">
                    现代、轻盈、系统感强的 Fluent 2 对话界面
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  {[
                    selectedAgent ? selectedAgent.agentName || selectedAgent.agentId : "未选择智能体",
                    sessionId ? "Session Active" : "Waiting",
                  ].map((item) => (
                    <span
                      key={item}
                      className="rounded-full border border-white/86 bg-white/74 px-3 py-1.5 text-[11px] font-medium text-[#4a6f9f]"
                    >
                      {item}
                    </span>
                  ))}
                </div>
              </div>
            </div>

            <div
              ref={chatRef}
              className="scrollbar-subtle flex-1 overflow-auto px-4 py-5 md:px-5"
            >
              {bubbles.length === 0 ? (
                <div className="surface-panel rounded-[28px] border border-white/84 px-5 py-5">
                  <p className="text-xs font-semibold uppercase tracking-[0.22em] text-[#6e84a1]">
                    Ready
                  </p>
                  <p className="mt-3 text-sm leading-7 text-[#49617f]">
                    选择智能体后开始对话。每次发送会先创建 `sessionId`，再调用 `chat`
                    接口。整个界面遵循 Fluent 2 的轻量空间反馈和系统式秩序。
                  </p>
                </div>
              ) : null}

              <div className="mt-4 space-y-3">
                {bubbles.map((bubble) => (
                  <div
                    key={bubble.id}
                    className={`flex ${
                      bubble.side === "user" ? "justify-start" : "justify-end"
                    }`}
                  >
                    <div
                      className={`max-w-[min(74%,920px)] rounded-[26px] px-4 py-3 text-sm leading-7 whitespace-pre-wrap shadow-[0_12px_24px_rgba(100,124,160,0.08)] ${
                        bubble.side === "user"
                          ? "border border-white/86 bg-[linear-gradient(180deg,rgba(255,255,255,0.92),rgba(246,249,255,0.84))] text-[#24415f]"
                          : "border border-[#d9e8ff] bg-[linear-gradient(180deg,rgba(235,243,255,0.96),rgba(223,236,255,0.84))] text-[#17345b]"
                      }`}
                    >
                      <div>{bubble.text}</div>
                      {bubble.meta ? (
                        <div className="mt-2 text-[11px] text-[#7387a4]">
                          {bubble.meta}
                        </div>
                      ) : null}
                    </div>
                  </div>
                ))}
              </div>
            </div>

            <div
              className={`min-h-5 px-5 pb-3 text-xs ${
                statusType === "error"
                  ? "text-[var(--danger)]"
                  : "text-[var(--muted-soft)]"
              }`}
            >
              {status}
            </div>

            <form
              onSubmit={handleSubmit}
              className="flex items-end gap-3 border-t border-[var(--line)] bg-[rgba(247,250,255,0.48)] p-4"
            >
              <textarea
                value={message}
                onChange={(event) => setMessage(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !event.shiftKey) {
                    event.preventDefault();
                    event.currentTarget.form?.requestSubmit();
                  }
                }}
                disabled={sending}
                placeholder="请输入问题，回车发送（Shift+Enter 换行）"
                className="ring-focus fluent-field min-h-[54px] max-h-[180px] flex-1 resize-none rounded-[22px] px-4 py-3 text-sm leading-7 disabled:cursor-not-allowed disabled:opacity-70"
              />
              <button
                type="submit"
                disabled={sending}
                className="hover-lift fluent-primary cursor-pointer rounded-[22px] px-5 py-3 font-semibold disabled:cursor-not-allowed disabled:opacity-70"
              >
                {sending ? "发送中…" : "发送"}
              </button>
            </form>
          </section>
        </div>
      </main>

      {backendIssue ? (
        <div className="fixed inset-0 z-30 flex items-center justify-center bg-[rgba(225,234,246,0.45)] px-4 backdrop-blur-md">
          <div className="glass-panel w-full max-w-xl overflow-hidden rounded-[32px]">
            <div className="flex items-center justify-between gap-3 border-b border-[var(--line)] px-5 py-4">
              <h3 className="text-sm font-semibold text-[#183352]">服务端接口不可用</h3>
              <button
                type="button"
                onClick={() => setBackendIssue(null)}
                className="hover-lift fluent-secondary cursor-pointer rounded-[18px] px-4 py-2 text-sm font-semibold"
              >
                关闭
              </button>
            </div>
            <div className="space-y-3 px-5 py-4">
              <p className="text-sm leading-7 text-[var(--muted)]">
                当前页面需要访问服务端接口。请先启动服务端，然后点击“重试”。如果服务端已启动但仍不可用，请检查前端环境变量
                `NEXT_PUBLIC_API_BASE` 是否指向正确地址。
              </p>
              <pre className="overflow-x-auto rounded-[22px] border border-white/82 bg-white/74 px-4 py-3 text-xs leading-6 text-[#2e4b74]">
{`API_BASE: ${apiBase}
curl -s ${apiBase}/api/v1/query_ai_agent_config_list

错误：${backendIssue}`}
              </pre>
            </div>
            <div className="flex justify-end gap-3 px-5 pb-5">
              <button
                type="button"
                onClick={async () => {
                  setBackendIssue(null);
                  setStatusMessage("正在重试连接服务端…");
                  await loadAgents();
                }}
                className="hover-lift fluent-secondary cursor-pointer rounded-[18px] px-4 py-2 text-sm font-semibold"
              >
                我已启动，重试
              </button>
              <button
                type="button"
                onClick={() => setBackendIssue(null)}
                className="hover-lift fluent-primary cursor-pointer rounded-[18px] px-4 py-2 text-sm font-semibold"
              >
                知道了
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
