"use client";

import Link from "next/link";
import { startTransition, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import AgentDrawIoPanel from "@/src/components/agent-drawio-panel";
import { useMounted } from "@/src/hooks/use-mounted";
import {
  createAgentSession,
  isBackendUnavailableError,
  extractDrawIoXml,
  streamChatWithAgent,
  submitChatStreamApproval,
} from "@/src/api/agent";
import { COOKIE_NAME, deleteCookieValue, formatTime } from "@/src/utils/cookie";
import type { AgentConfig, ChatStreamMessage } from "@/src/types/api";

type ChatStreamClientProps = {
  apiBase: string;
  initialAgents: AgentConfig[];
  initialBackendIssue: string | null;
  userId: string;
  loginTs: number | null;
};

type StreamLogItem = {
  id: string;
  type: ChatStreamMessage["type"];
  stage: string;
  content: string;
  timestamp: number;
};

function createLogItem(message: ChatStreamMessage): StreamLogItem {
  return {
    id: `${message.type}-${message.timestamp ?? Date.now()}-${Math.random().toString(16).slice(2)}`,
    type: message.type,
    stage: message.stage || "system",
    content: message.content || "",
    timestamp: message.timestamp ?? Date.now(),
  };
}

type ApprovalEntry = {
  approvalId: string;
  requestId: string;
  commandType?: string;
  hostName?: string;
  command: string;
  reason?: string;
  expiresAt?: number;
  status: string;
  submitting: boolean;
};

const APPROVAL_STATUS_TEXT: Record<string, string> = {
  pending: "等待你的决定",
  submitting: "正在提交决定…",
  approved: "已批准（允许一次），命令继续执行",
  rejected: "已拒绝，命令不会执行",
  expired: "审批已过期，命令不会执行",
  cancelled: "审批已取消（流式连接断开或已停止）",
  already_resolved: "该审批已被处理，本次提交无效",
  not_found: "审批不存在或已被清理",
  request_mismatch: "requestId 与 approvalId 不匹配",
  not_applied: "审批未生效",
  ended: "本次流式请求已结束，审批已失效",
};

function isApprovalActionable(entry: ApprovalEntry | null) {
  return entry !== null && entry.status === "pending" && !entry.submitting;
}

function isApprovalAwaiting(entry: ApprovalEntry | null) {
  return entry !== null && (entry.status === "pending" || entry.status === "submitting");
}

function formatApprovalCommand(message: ChatStreamMessage) {
  const lines = [message.content || ""];
  if (message.commandType) {
    lines.push(`目标：${message.commandType}${message.hostName ? ` / ${message.hostName}` : ""}`);
  }
  if (message.reason) {
    lines.push(`原因：${message.reason}`);
  }
  return lines.filter((line) => line.trim()).join("\n");
}

function logItemClass(type: ChatStreamMessage["type"]) {
  if (type === "error") return "border-[#ffd5d5] bg-[#fff3f3] text-[#8a2d2d]";
  if (type === "done") return "border-[#dce9ff] bg-[#eef5ff] text-[#26456e]";
  if (type === "approval_required") return "border-[#ffdca3] bg-[#fff6e3] text-[#6f5412]";
  if (type === "approval_resolved") return "border-[#c9ecd9] bg-[#eefaf3] text-[#1e6b44]";
  return "border-white/84 bg-white/72 text-[#2a4568]";
}

export default function ChatStreamClient({
  apiBase,
  initialAgents,
  initialBackendIssue,
  userId,
  loginTs,
}: ChatStreamClientProps) {
  const router = useRouter();
  const abortRef = useRef<AbortController | null>(null);
  const currentRequestIdRef = useRef<string>("");
  const mounted = useMounted();
  const [agents] = useState(initialAgents);
  const [selectedAgentId, setSelectedAgentId] = useState(
    initialAgents[0]?.agentId ?? "",
  );
  const [sessionId, setSessionId] = useState("");
  const [message, setMessage] = useState("");
  const [latestSubmittedMessage, setLatestSubmittedMessage] = useState("");
  const [sending, setSending] = useState(false);
  const [status, setStatus] = useState(
    initialBackendIssue ? `接口不可用：${initialBackendIssue}` : "准备开始流式联调。",
  );
  const [statusType, setStatusType] = useState<"info" | "error">(
    initialBackendIssue ? "error" : "info",
  );
  const [logs, setLogs] = useState<StreamLogItem[]>([]);
  const [resultText, setResultText] = useState("");
  const [resultStage, setResultStage] = useState("result");
  const [diagramXml, setDiagramXml] = useState<string | null>(null);
  const [backendIssue, setBackendIssue] = useState<string | null>(
    initialBackendIssue,
  );
  const [pendingApproval, setPendingApproval] = useState<ApprovalEntry | null>(
    null,
  );

  const selectedAgent = useMemo(
    () => agents.find((agent) => agent.agentId === selectedAgentId) ?? null,
    [agents, selectedAgentId],
  );

  function setStatusMessage(nextStatus: string, type: "info" | "error" = "info") {
    setStatus(nextStatus);
    setStatusType(type);
  }

  function resetDebugState() {
    abortRef.current?.abort();
    abortRef.current = null;
    setSending(false);
    setSessionId("");
    setMessage("");
    setLatestSubmittedMessage("");
    setLogs([]);
    setResultText("");
    setResultStage("result");
    setDiagramXml(null);
    setPendingApproval(null);
    setStatusMessage("已重置流式测试状态。");
  }

  function endPendingApproval() {
    setPendingApproval((current) =>
      isApprovalAwaiting(current)
        ? { ...current, submitting: false, status: "ended" }
        : current,
    );
  }

  async function handleApprovalDecision(decision: "approve_once" | "reject") {
    if (!pendingApproval || !isApprovalActionable(pendingApproval)) {
      return;
    }
    const target = pendingApproval;
    setPendingApproval({ ...target, submitting: true, status: "submitting" });

    try {
      const data = await submitChatStreamApproval(target.requestId, {
        approvalId: target.approvalId,
        decision,
      });
      const nextStatus = data?.status || (decision === "approve_once" ? "approved" : "rejected");
      setPendingApproval((current) =>
        current && current.approvalId === target.approvalId
          ? { ...current, submitting: false, status: nextStatus }
          : current,
      );
      setLogs((current) => [
        ...current,
        {
          id: `approval-decision-${Date.now()}-${Math.random().toString(16).slice(2)}`,
          type: "approval_resolved",
          stage: "approval",
          content: `用户提交：${decision === "approve_once" ? "允许运行" : "拒绝"}（${nextStatus}）`,
          timestamp: Date.now(),
        },
      ]);
      setStatusMessage(
        decision === "approve_once" ? "已提交审批决定：允许运行。" : "已提交审批决定：拒绝。",
      );
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      setPendingApproval((current) =>
        current && current.approvalId === target.approvalId
          ? { ...current, submitting: false, status: "pending" }
          : current,
      );
      setStatusMessage(`审批提交失败：${reason}`, "error");
    }
  }

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmedMessage = message.trim();

    if (!selectedAgentId) {
      setStatusMessage("请先选择智能体。", "error");
      return;
    }

    if (!trimmedMessage || sending) {
      return;
    }

    const controller = new AbortController();
    abortRef.current?.abort();
    abortRef.current = controller;

    setSending(true);
    setLogs([]);
    setResultText("");
    setResultStage("result");
    setDiagramXml(null);
    setLatestSubmittedMessage(trimmedMessage);
    setPendingApproval(null);
    setStatusMessage("正在连接流式接口…");

    try {
      const nextSessionId =
        sessionId ||
        (await createAgentSession({
          agentId: selectedAgentId,
          userId,
        }));
      setSessionId(nextSessionId);

      await streamChatWithAgent(
        {
          agentId: selectedAgentId,
          userId,
          sessionId: nextSessionId,
          message: trimmedMessage,
        },
        {
          signal: controller.signal,
          onMessage: (streamMessage) => {
            if (streamMessage.requestId) {
              currentRequestIdRef.current = streamMessage.requestId;
            }

            if (streamMessage.type === "approval_required") {
              if (streamMessage.approvalId) {
                setPendingApproval({
                  approvalId: streamMessage.approvalId,
                  requestId: streamMessage.requestId || currentRequestIdRef.current,
                  commandType: streamMessage.commandType,
                  hostName: streamMessage.hostName,
                  command: streamMessage.content || "",
                  reason: streamMessage.reason,
                  expiresAt: streamMessage.expiresAt,
                  status: "pending",
                  submitting: false,
                });
              }
              setLogs((current) => [
                ...current,
                createLogItem({
                  type: "approval_required",
                  stage: "approval",
                  requestId: streamMessage.requestId,
                  timestamp: streamMessage.timestamp,
                  content: formatApprovalCommand(streamMessage),
                }),
              ]);
              setStatusMessage("收到命令审批请求，“允许运行 / 拒绝”按钮已亮起，请决定。");
              return;
            }

            if (streamMessage.type === "approval_resolved") {
              const resolvedStatus = (streamMessage.content || "").toLowerCase();
              setPendingApproval((current) =>
                current && current.approvalId === streamMessage.approvalId
                  ? { ...current, submitting: false, status: resolvedStatus }
                  : current,
              );
              setLogs((current) => [
                ...current,
                createLogItem({
                  type: "approval_resolved",
                  stage: "approval",
                  requestId: streamMessage.requestId,
                  timestamp: streamMessage.timestamp,
                  content: `审批结果：${APPROVAL_STATUS_TEXT[resolvedStatus] ?? resolvedStatus}`,
                }),
              ]);
              return;
            }

            if (streamMessage.type === "log") {
              setLogs((current) => [...current, createLogItem(streamMessage)]);
              setStatusMessage("正在接收日志流…");
              return;
            }

            if (streamMessage.type === "result") {
              const nextContent = streamMessage.content || "";
              const drawioXml = extractDrawIoXml(nextContent);
              setResultText(nextContent);
              setResultStage(streamMessage.stage || "result");
              if (drawioXml) {
                setDiagramXml(drawioXml);
                setStatusMessage("已收到最终 draw.io 结果。");
              } else {
                setDiagramXml(null);
                setStatusMessage("已收到最终文本结果。");
              }
              return;
            }

            if (streamMessage.type === "done") {
              setLogs((current) => [...current, createLogItem(streamMessage)]);
              setStatusMessage("流式任务完成。");
              endPendingApproval();
              return;
            }

            if (streamMessage.type === "error") {
              setLogs((current) => [...current, createLogItem(streamMessage)]);
              setStatusMessage(streamMessage.content || "流式任务失败", "error");
              endPendingApproval();
            }
          },
        },
      );
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") {
        setStatusMessage("已停止当前流式请求。");
      } else {
        const messageText = error instanceof Error ? error.message : "流式请求失败";
        setStatusMessage(`失败：${messageText}`, "error");
        if (isBackendUnavailableError(error)) {
          setBackendIssue(messageText);
        }
      }
    } finally {
      setSending(false);
      abortRef.current = null;
      endPendingApproval();
    }
  }

  function handleStop() {
    abortRef.current?.abort();
    abortRef.current = null;
    setSending(false);
    endPendingApproval();
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
              ST
            </div>
            <div className="min-w-0 pt-0.5">
              <strong className="block truncate text-sm leading-5 text-[#1a3455]">
                chat_stream 接口联调页
              </strong>
              <span className="mt-0.5 block truncate text-xs leading-5 text-[var(--muted-soft)]">
                API: {apiBase}
              </span>
            </div>
          </div>

          <div className="flex flex-wrap items-start justify-end gap-2">
            <select
              value={selectedAgentId}
              onChange={(event) => setSelectedAgentId(event.target.value)}
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
                {loginTs && mounted ? `登录于 ${formatTime(loginTs)}` : "已登录"}
              </span>
            </div>

            <Link
              href="/chat"
              className="hover-lift fluent-secondary flex h-[56px] items-center justify-center rounded-[18px] px-5 text-sm font-semibold"
            >
              返回会话页
            </Link>

            <button
              type="button"
              onClick={handleLogout}
              className="hover-lift fluent-secondary flex h-[56px] items-center justify-center rounded-[18px] px-6 text-sm font-semibold"
            >
              退出
            </button>
          </div>
        </div>
      </header>

      <main className="flex min-h-0 flex-1 overflow-hidden px-2 py-3 md:px-3 md:py-4">
        <div className="grid h-full min-h-0 w-full min-w-0 gap-3 md:gap-4 lg:grid-cols-[1.4fr_1fr_1.15fr]">
          <section className="glass-panel flex min-h-0 min-w-0 flex-col overflow-hidden rounded-[34px]">
            <div className="border-b border-[var(--line)] px-5 py-4">
              <h1 className="text-lg font-semibold tracking-tight text-[#17304d]">
                流式日志与结果
              </h1>
              <p className="mt-1 text-sm text-[var(--muted-soft)]">
                日志通过同一个 `ResponseBodyEmitter` 实时推送，最终结果在任务完成后统一返回。
              </p>
            </div>

            <div className="grid min-h-0 flex-1 gap-3 overflow-hidden p-4">
              <div className="surface-panel min-h-0 rounded-[28px] border border-white/84 p-4">
                <div className="mb-3 flex items-center justify-between">
                  <h2 className="text-sm font-semibold text-[#1f3657]">日志流</h2>
                  <span className="text-xs text-[var(--muted-soft)]">
                    {logs.length} 条
                  </span>
                </div>
                <div className="scrollbar-subtle h-[38vh] space-y-2 overflow-auto pr-1">
                  {logs.length === 0 ? (
                    <p className="text-sm leading-6 text-[var(--muted-soft)]">
                      发送后，这里会按 `log / error / done` 顺序显示流式消息。
                    </p>
                  ) : (
                    logs.map((item) => (
                      <div
                        key={item.id}
                        className={`rounded-[20px] border px-3 py-3 text-sm ${logItemClass(item.type)}`}
                      >
                        <div className="flex items-center justify-between gap-3 text-[11px]">
                          <span className="font-semibold uppercase tracking-[0.18em]">
                            {item.type}
                          </span>
                          <span className="text-[#71849e]">
                            {formatTime(item.timestamp)}
                          </span>
                        </div>
                        <div className="mt-2 text-[11px] text-[#6c81a0]">
                          stage: {item.stage}
                        </div>
                        <div className="mt-2 whitespace-pre-wrap break-all leading-6">
                          {item.content || "(空内容)"}
                        </div>
                      </div>
                    ))
                  )}
                </div>
              </div>

              <div className="surface-panel rounded-[28px] border border-white/84 p-4">
                <div className="mb-3 flex items-center justify-between">
                  <h2 className="text-sm font-semibold text-[#1f3657]">最终结果</h2>
                  <span className="rounded-full border border-white/84 bg-white/76 px-3 py-1 text-[11px] font-medium text-[#4a6f9f]">
                    {resultStage}
                  </span>
                </div>
                <div className="scrollbar-subtle h-[18vh] overflow-auto rounded-[22px] border border-white/82 bg-white/70 px-4 py-3 text-sm leading-7 whitespace-pre-wrap text-[#26456e]">
                  {resultText || "等待最终结果…"}
                </div>
              </div>
            </div>
          </section>

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
            latestUserMessage={latestSubmittedMessage}
            latestAgentMessage={resultText}
            latestAgentMeta={status}
            diagramXml={diagramXml}
          />

          <section className="glass-panel flex min-h-0 min-w-0 flex-col overflow-hidden rounded-[34px]">
            <div className="border-b border-[var(--line)] px-5 py-4">
              <h2 className="text-sm font-semibold text-[#1f3657]">请求控制</h2>
              <p className="mt-1 text-sm text-[var(--muted-soft)]">
                当前 Session 会复用，方便你连续观察同一个会话下的日志和最终结果。
              </p>
            </div>

            <div className="flex-1 overflow-auto p-4">
              <div className="surface-panel rounded-[28px] border border-white/84 p-4">
                <div className="grid gap-3 text-sm text-[#2c496d]">
                  <div className="rounded-[20px] border border-white/82 bg-white/72 px-4 py-3">
                    <div className="text-[11px] uppercase tracking-[0.18em] text-[#6b82a0]">
                      Session ID
                    </div>
                    <div className="mt-2 break-all font-medium">
                      {sessionId || "首次发送后自动创建"}
                    </div>
                  </div>
                  <div className="rounded-[20px] border border-white/82 bg-white/72 px-4 py-3">
                    <div className="text-[11px] uppercase tracking-[0.18em] text-[#6b82a0]">
                      状态
                    </div>
                    <div
                      className={`mt-2 font-medium ${
                        statusType === "error" ? "text-[var(--danger)]" : "text-[#26456e]"
                      }`}
                    >
                      {status}
                    </div>
                  </div>
                </div>

                <form onSubmit={handleSubmit} className="mt-4 space-y-3">
                  <textarea
                    value={message}
                    onChange={(event) => setMessage(event.target.value)}
                    disabled={sending}
                    placeholder="输入一段让智能体绘图或补充信息的提示词，观察日志和最终结果。"
                    className="ring-focus fluent-field min-h-[180px] w-full resize-none rounded-[24px] px-4 py-3 text-sm leading-7 disabled:cursor-not-allowed disabled:opacity-70"
                  />

                  {pendingApproval ? (
                    <div className="rounded-[20px] border border-[#ffdca3] bg-[#fff6e3] px-4 py-3 text-xs leading-6 text-[#6f5412]">
                      <span className="font-semibold">待审批命令：</span>
                      <span className="break-all font-mono">
                        {pendingApproval.command || "(空)"}
                      </span>
                      {pendingApproval.reason ? (
                        <span className="mt-0.5 block text-[11px] text-[#8a6d2a]">
                          原因：{pendingApproval.reason}
                        </span>
                      ) : null}
                      <span className="mt-1 block text-[11px] text-[#9a8459]">
                        状态：{APPROVAL_STATUS_TEXT[pendingApproval.status] ?? pendingApproval.status}
                        {pendingApproval.expiresAt
                          ? ` · 过期时间 ${new Date(pendingApproval.expiresAt).toLocaleTimeString()}`
                          : ""}
                      </span>
                    </div>
                  ) : null}

                  <div className="flex flex-wrap items-center gap-3">
                    <button
                      type="submit"
                      disabled={sending}
                      className="hover-lift fluent-primary cursor-pointer rounded-[20px] px-5 py-3 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-70"
                    >
                      {sending ? "流式接收中…" : "开始测试"}
                    </button>
                    <button
                      type="button"
                      onClick={handleStop}
                      disabled={!sending}
                      className="hover-lift fluent-secondary cursor-pointer rounded-[20px] px-5 py-3 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-70"
                    >
                      停止
                    </button>
                    <button
                      type="button"
                      onClick={resetDebugState}
                      className="hover-lift fluent-secondary cursor-pointer rounded-[20px] px-5 py-3 text-sm font-semibold"
                    >
                      重置
                    </button>
                    <span className="mx-1 hidden h-6 w-px bg-[var(--line)] sm:block" />
                    <button
                      type="button"
                      onClick={() => handleApprovalDecision("approve_once")}
                      disabled={!isApprovalActionable(pendingApproval)}
                      title={isApprovalActionable(pendingApproval) ? "批准并运行待审批命令" : "等待审批请求"}
                      className={`cursor-pointer rounded-[20px] px-5 py-3 text-sm font-semibold transition disabled:cursor-not-allowed ${
                        isApprovalActionable(pendingApproval)
                          ? "animate-pulse bg-[#0f6cfd] text-white shadow-[0_0_0_4px_rgba(15,108,253,0.2)] hover:opacity-90"
                          : "bg-white/60 text-[#9aa7ba] opacity-60"
                      }`}
                    >
                      允许运行
                    </button>
                    <button
                      type="button"
                      onClick={() => handleApprovalDecision("reject")}
                      disabled={!isApprovalActionable(pendingApproval)}
                      title={isApprovalActionable(pendingApproval) ? "拒绝执行待审批命令" : "等待审批请求"}
                      className={`cursor-pointer rounded-[20px] px-5 py-3 text-sm font-semibold transition disabled:cursor-not-allowed ${
                        isApprovalActionable(pendingApproval)
                          ? "animate-pulse bg-[#a3312f] text-white shadow-[0_0_0_4px_rgba(163,49,47,0.2)] hover:opacity-90"
                          : "bg-white/60 text-[#9aa7ba] opacity-60"
                      }`}
                    >
                      拒绝
                    </button>
                  </div>
                </form>
              </div>
            </div>
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
                当前测试页需要访问 `chat_stream` 接口。请确认后端已启动，并检查
                `NEXT_PUBLIC_API_BASE` 是否指向正确地址。
              </p>
              <pre className="overflow-x-auto rounded-[22px] border border-white/82 bg-white/74 px-4 py-3 text-xs leading-6 text-[#2e4b74]">
{`API_BASE: ${apiBase}
POST ${apiBase}/api/v1/chat_stream

错误：${backendIssue}`}
              </pre>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
