"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import {
  isBackendUnavailableError,
  queryCurrentAgentConfig,
  updateAgentConfig,
} from "@/src/api/agent";
import { deleteCookieValue, formatTime, COOKIE_NAME } from "@/src/utils/cookie";
import type { CurrentAgentConfigData, JsonValue } from "@/src/types/api";

type AgentConfigClientProps = {
  apiBase: string;
  initialBackendIssue: string | null;
  initialConfig: CurrentAgentConfigData;
  userId: string;
  loginTs: number | null;
};

function formatTables(tables: CurrentAgentConfigData["tables"]) {
  return JSON.stringify(tables, null, 2);
}

function parseTablesJson(text: string) {
  const trimmed = text.trim();
  if (!trimmed) {
    return {};
  }

  const parsed = JSON.parse(trimmed) as JsonValue;
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("tables 必须是一个 JSON 对象");
  }

  return parsed as CurrentAgentConfigData["tables"];
}

function buildSummaryEntries(config: CurrentAgentConfigData) {
  return Object.entries(config.tables).map(([key, value]) => {
    const table =
      value && typeof value === "object" && !Array.isArray(value)
        ? (value as Record<string, unknown>)
        : null;
    const appName =
      table && typeof table.appName === "string" ? table.appName : "-";
    const agentInfo =
      table &&
      table.agent &&
      typeof table.agent === "object" &&
      !Array.isArray(table.agent)
        ? (table.agent as Record<string, unknown>)
        : null;

    const agentLabel =
      agentInfo && typeof agentInfo.agentName === "string"
        ? agentInfo.agentName
        : agentInfo && typeof agentInfo.agentId === "string"
          ? agentInfo.agentId
          : "-";

    return {
      key,
      appName,
      agentLabel,
    };
  });
}

export default function AgentConfigClient({
  apiBase,
  initialBackendIssue,
  initialConfig,
  userId,
  loginTs,
}: AgentConfigClientProps) {
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const [enabled, setEnabled] = useState(initialConfig.enabled);
  const [tablesText, setTablesText] = useState(formatTables(initialConfig.tables));
  const [status, setStatus] = useState(
    initialBackendIssue ? `Agent 配置加载失败：${initialBackendIssue}` : "当前配置已加载。",
  );
  const [statusType, setStatusType] = useState<"info" | "error">(
    initialBackendIssue ? "error" : "info",
  );
  const [backendIssue, setBackendIssue] = useState<string | null>(
    initialBackendIssue,
  );
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(false);
  const [lastSavedConfig, setLastSavedConfig] = useState(initialConfig);

  const summaryEntries = buildSummaryEntries({
    enabled,
    tables: (() => {
      try {
        return parseTablesJson(tablesText);
      } catch {
        return {};
      }
    })(),
  });

  useEffect(() => {
    textareaRef.current?.focus();
  }, []);

  function setStatusMessage(nextStatus: string, type: "info" | "error" = "info") {
    setStatus(nextStatus);
    setStatusType(type);
  }

  async function handleReload() {
    setLoading(true);
    try {
      const latestConfig = await queryCurrentAgentConfig({ method: "GET" });
      setEnabled(latestConfig.enabled);
      setTablesText(formatTables(latestConfig.tables));
      setLastSavedConfig(latestConfig);
      setBackendIssue(null);
      setStatusMessage("已从服务端重新加载最新配置。");
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Agent 配置加载失败";
      setStatusMessage(`加载失败：${message}`, "error");
      if (isBackendUnavailableError(error)) {
        setBackendIssue(message);
      }
    } finally {
      setLoading(false);
    }
  }

  async function handleSave() {
    setSaving(true);
    try {
      const nextTables = parseTablesJson(tablesText);
      const nextConfig = await updateAgentConfig({
        enabled,
        tables: nextTables,
      });

      setEnabled(nextConfig.enabled);
      setTablesText(formatTables(nextConfig.tables));
      setLastSavedConfig(nextConfig);
      setBackendIssue(null);
      setStatusMessage("配置已更新，Agent 已重新装配。");
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Agent 配置更新失败";
      setStatusMessage(`保存失败：${message}`, "error");
      if (isBackendUnavailableError(error)) {
        setBackendIssue(message);
      }
    } finally {
      setSaving(false);
    }
  }

  function handleReset() {
    setEnabled(lastSavedConfig.enabled);
    setTablesText(formatTables(lastSavedConfig.tables));
    setStatusMessage("已恢复到最近一次成功加载/保存的配置。");
  }

  function handleLogout() {
    deleteCookieValue(COOKIE_NAME);
    window.location.href = "/login";
  }

  return (
    <div className="relative flex min-h-screen w-full flex-col overflow-hidden">
      <div className="pointer-events-none absolute left-[10%] top-8 h-52 w-52 rounded-full bg-[radial-gradient(circle,rgba(148,192,255,0.48),rgba(148,192,255,0))]" />
      <div className="pointer-events-none absolute bottom-10 right-[8%] h-72 w-72 rounded-full bg-[radial-gradient(circle,rgba(193,221,255,0.72),rgba(193,221,255,0))]" />

      <header className="sticky top-0 z-10 border-b border-white/58 bg-[rgba(247,250,255,0.62)] backdrop-blur-[26px]">
        <div className="flex w-full flex-wrap items-start justify-between gap-x-4 gap-y-3 px-3 py-3.5 md:px-4 md:py-4">
          <div className="flex min-w-0 items-start gap-3">
            <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-[18px] bg-[linear-gradient(180deg,#ffffff,#dceafe)] text-sm font-bold tracking-[0.08em] text-[#0f6cfd] shadow-[0_10px_24px_rgba(107,140,194,0.16)]">
              AC
            </div>
            <div className="min-w-0 pt-0.5">
              <strong className="block truncate text-sm leading-5 text-[#1a3455]">
                Agent 配置中心
              </strong>
              <span className="mt-0.5 block truncate text-xs leading-5 text-[var(--muted-soft)]">
                API: {apiBase}
              </span>
            </div>
          </div>

          <div className="flex flex-wrap items-start justify-end gap-2">
            <Link
              href="/chat"
              className="hover-lift fluent-secondary flex h-[56px] items-center justify-center rounded-[18px] px-5 text-sm font-semibold"
            >
              返回对话
            </Link>

            <div className="surface-panel flex h-[56px] min-w-[210px] flex-col justify-center rounded-[18px] border border-white/82 px-4">
              <b className="block text-xs leading-4 text-[#213c61]">{userId}</b>
              <span className="mt-0.5 block text-xs leading-4 text-[var(--muted-soft)]">
                {loginTs ? `登录于 ${formatTime(loginTs)}` : "已登录"}
              </span>
            </div>

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
        <div className="grid h-full min-h-0 w-full min-w-0 gap-3 md:gap-4 lg:grid-cols-[320px_1fr]">
          <aside className="glass-panel min-h-0 min-w-0 overflow-hidden rounded-[34px]">
            <div className="border-b border-[var(--line)] px-5 py-4">
              <p className="text-[11px] font-semibold uppercase tracking-[0.22em] text-[#5d7fad]">
                Overview
              </p>
              <h2 className="mt-1 text-base font-semibold text-[#1f3657]">
                当前配置摘要
              </h2>
            </div>

            <div className="scrollbar-subtle h-full space-y-3 overflow-auto px-4 py-4">
              <div className="surface-panel rounded-[24px] border border-white/84 px-4 py-4">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-semibold text-[#1f3657]">装配状态</span>
                  <span
                    className={`rounded-full px-3 py-1 text-[11px] font-semibold ${
                      enabled
                        ? "bg-[rgba(15,108,253,0.12)] text-[#0f5bd3]"
                        : "bg-[rgba(106,127,155,0.14)] text-[#61748f]"
                    }`}
                  >
                    {enabled ? "Enabled" : "Disabled"}
                  </span>
                </div>
                <p className="mt-3 text-xs leading-6 text-[var(--muted-soft)]">
                  当前共有 {summaryEntries.length} 个配置表。保存后会调用后端重装 Agent。
                </p>
              </div>

              <div className="surface-panel rounded-[24px] border border-white/84 px-4 py-4">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-semibold text-[#1f3657]">配置表</span>
                  <span className="text-[11px] text-[#6b82a0]">tables</span>
                </div>
                {summaryEntries.length === 0 ? (
                  <p className="mt-3 text-xs leading-6 text-[var(--muted-soft)]">
                    当前没有可用的配置表，你可以直接在右侧 JSON 编辑器中新增。
                  </p>
                ) : (
                  <div className="mt-3 space-y-2.5">
                    {summaryEntries.map((item) => (
                      <div
                        key={item.key}
                        className="rounded-[18px] border border-white/84 bg-white/70 px-3 py-3"
                      >
                        <div className="truncate text-sm font-semibold text-[#1f3657]">
                          {item.key}
                        </div>
                        <div className="mt-1 text-[11px] text-[#6b82a0]">
                          appName: {item.appName}
                        </div>
                        <div className="mt-1 text-[11px] text-[#6b82a0]">
                          agent: {item.agentLabel}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </aside>

          <section className="glass-panel flex min-h-0 min-w-0 flex-col overflow-hidden rounded-[34px]">
            <div className="border-b border-[var(--line)] px-5 py-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <h1 className="text-lg font-semibold tracking-tight text-[#17304d]">
                    动态修改 Agent 配置
                  </h1>
                  <p className="mt-1 text-sm text-[var(--muted-soft)]">
                    页面会先读取当前生效配置；编辑后保存，会调用后端重新装配 Agent。
                  </p>
                </div>

                <div className="flex flex-wrap gap-2">
                  <button
                    type="button"
                    onClick={handleReload}
                    disabled={loading || saving}
                    className="hover-lift fluent-secondary rounded-[18px] px-4 py-2.5 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-70"
                  >
                    {loading ? "加载中…" : "重新读取"}
                  </button>
                  <button
                    type="button"
                    onClick={handleReset}
                    disabled={saving}
                    className="hover-lift fluent-secondary rounded-[18px] px-4 py-2.5 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-70"
                  >
                    重置
                  </button>
                  <button
                    type="button"
                    onClick={handleSave}
                    disabled={saving || loading}
                    className="hover-lift fluent-primary rounded-[18px] px-4 py-2.5 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-70"
                  >
                    {saving ? "保存中…" : "保存并重装配"}
                  </button>
                </div>
              </div>
            </div>

            <div className="scrollbar-subtle flex-1 overflow-auto px-5 py-5">
              <div className="surface-panel mb-4 rounded-[28px] border border-white/84 px-5 py-5">
                <label className="flex items-center gap-3">
                  <input
                    type="checkbox"
                    checked={enabled}
                    onChange={(event) => setEnabled(event.target.checked)}
                    className="h-4 w-4 accent-[#0f6cfd]"
                  />
                  <span className="text-sm font-semibold text-[#1f3657]">
                    启用 AI Agent 自动装配
                  </span>
                </label>
                <p className="mt-3 text-xs leading-6 text-[var(--muted-soft)]">
                  右侧编辑的是 `tables` 内容，需保持为合法 JSON 对象结构。
                </p>
              </div>

              <div className="surface-panel rounded-[28px] border border-white/84 p-4">
                <div className="mb-3 flex items-center justify-between">
                  <div>
                    <h2 className="text-sm font-semibold text-[#1f3657]">
                      tables JSON
                    </h2>
                    <p className="mt-1 text-xs text-[var(--muted-soft)]">
                      可直接修改配置表内容后提交
                    </p>
                  </div>
                  <span className="rounded-full border border-white/86 bg-white/72 px-3 py-1 text-[11px] font-medium text-[#4a6f9f]">
                    JSON Editor
                  </span>
                </div>

                <textarea
                  ref={textareaRef}
                  value={tablesText}
                  onChange={(event) => setTablesText(event.target.value)}
                  spellCheck={false}
                  className="ring-focus fluent-field scrollbar-subtle min-h-[560px] w-full resize-y rounded-[24px] px-4 py-4 font-mono text-[13px] leading-6"
                  placeholder='{"testAgent03": {}}'
                />
              </div>
            </div>

            <div
              className={`min-h-5 px-5 pb-4 text-xs ${
                statusType === "error"
                  ? "text-[var(--danger)]"
                  : "text-[var(--muted-soft)]"
              }`}
            >
              {status}
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
                className="hover-lift fluent-secondary rounded-[18px] px-4 py-2 text-sm font-semibold"
              >
                关闭
              </button>
            </div>
            <div className="space-y-3 px-5 py-4">
              <p className="text-sm leading-7 text-[var(--muted)]">
                当前页面需要访问服务端管理接口。请确认服务端已启动，且前端环境变量
                `NEXT_PUBLIC_API_BASE` 指向正确地址。
              </p>
              <pre className="overflow-x-auto rounded-[22px] border border-white/82 bg-white/74 px-4 py-3 text-xs leading-6 text-[#2e4b74]">
{`API_BASE: ${apiBase}
curl -s ${apiBase}/api/v1/admin/query_current_agent_config

错误：${backendIssue}`}
              </pre>
            </div>
            <div className="flex justify-end gap-3 px-5 pb-5">
              <button
                type="button"
                onClick={async () => {
                  setBackendIssue(null);
                  setStatusMessage("正在重试读取 Agent 配置…");
                  await handleReload();
                }}
                className="hover-lift fluent-secondary rounded-[18px] px-4 py-2 text-sm font-semibold"
              >
                我已启动，重试
              </button>
              <button
                type="button"
                onClick={() => setBackendIssue(null)}
                className="hover-lift fluent-primary rounded-[18px] px-4 py-2 text-sm font-semibold"
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
