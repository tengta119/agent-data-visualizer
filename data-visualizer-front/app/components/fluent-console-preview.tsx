type FluentConsolePreviewProps = {
  compact?: boolean;
};

export default function FluentConsolePreview({
  compact = false,
}: FluentConsolePreviewProps) {
  return (
    <div
      className={`fluent-accent-border relative overflow-hidden rounded-[30px] ${
        compact ? "p-3" : "p-4"
      }`}
    >
      <div className="surface-panel relative overflow-hidden rounded-[28px] border border-white/80 px-4 py-4">
        <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_top_left,rgba(255,255,255,0.9),transparent_44%),radial-gradient(circle_at_82%_18%,rgba(116,176,255,0.28),transparent_34%),linear-gradient(180deg,rgba(255,255,255,0.2),transparent)]" />

        <div className="relative">
          <div className="mb-4 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <span className="h-2.5 w-2.5 rounded-full bg-[#ff6b6b]" />
              <span className="h-2.5 w-2.5 rounded-full bg-[#ffd166]" />
              <span className="h-2.5 w-2.5 rounded-full bg-[#4cd964]" />
            </div>
            <div className="rounded-full border border-white/70 bg-white/70 px-3 py-1 text-[11px] font-medium text-[#335179]">
              Fluent Control Center
            </div>
          </div>

          <div className={`grid gap-3 ${compact ? "grid-cols-1" : "md:grid-cols-[0.78fr_1.22fr]"}`}>
            <div className="space-y-3">
              <div className="rounded-[24px] border border-white/70 bg-white/66 p-4 shadow-[inset_0_1px_0_rgba(255,255,255,0.82)]">
                <div className="mb-3 flex items-center justify-between">
                  <span className="text-xs font-semibold text-[#2b466d]">
                    Workspace
                  </span>
                  <span className="rounded-full bg-[#e8f1ff] px-2 py-1 text-[10px] font-medium text-[#0f6cfd]">
                    Active
                  </span>
                </div>
                <div className="space-y-2">
                  {["智能体编排", "MCP 工具接入", "会话上下文"].map((item) => (
                    <div
                      key={item}
                      className="flex items-center gap-3 rounded-2xl bg-[#f7faff] px-3 py-2"
                    >
                      <span className="h-8 w-8 rounded-2xl bg-[linear-gradient(180deg,#f4f8ff,#dfeaff)] shadow-[inset_0_1px_0_rgba(255,255,255,0.82)]" />
                      <span className="text-xs font-medium text-[#2e476e]">
                        {item}
                      </span>
                    </div>
                  ))}
                </div>
              </div>

              {!compact ? (
                <div className="rounded-[24px] border border-white/70 bg-white/60 p-4">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold text-[#2b466d]">
                      系统状态
                    </span>
                    <span className="text-[11px] text-[#6b7d98]">Windows-like</span>
                  </div>
                  <div className="mt-3 space-y-2.5">
                    {[
                      ["连接状态", "95%"],
                      ["工具可用性", "88%"],
                      ["响应延迟", "72%"],
                    ].map(([label, value], index) => (
                      <div key={label}>
                        <div className="mb-1 flex justify-between text-[11px] text-[#5f728e]">
                          <span>{label}</span>
                          <span>{value}</span>
                        </div>
                        <div className="h-2 rounded-full bg-[#e8eef8]">
                          <div
                            className="h-2 rounded-full bg-[linear-gradient(90deg,#0f6cfd,#77a7ff)]"
                            style={{ width: `${95 - index * 12}%` }}
                          />
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              ) : null}
            </div>

            <div className="rounded-[26px] border border-white/76 bg-[linear-gradient(180deg,rgba(255,255,255,0.86),rgba(243,247,255,0.72))] p-4">
              <div className="mb-3 flex items-center justify-between">
                <div>
                  <h3 className="text-sm font-semibold text-[#203756]">
                    Agent Conversation
                  </h3>
                  <p className="mt-1 text-[11px] text-[#6f8099]">
                    系统级控制中枢 · 柔和层次 · 亚克力交互
                  </p>
                </div>
                <div className="rounded-2xl bg-[linear-gradient(180deg,#f7faff,#e5efff)] px-3 py-2 text-[11px] font-medium text-[#0f6cfd] shadow-[inset_0_1px_0_rgba(255,255,255,0.95)]">
                  Fluent 2
                </div>
              </div>

              <div className="space-y-3">
                <div className="max-w-[75%] rounded-[24px] rounded-bl-xl border border-white/76 bg-white/84 px-4 py-3 text-[12px] leading-6 text-[#24415f] shadow-[0_8px_18px_rgba(103,128,164,0.1)]">
                  帮我查看当前可用的智能体，并生成一份适合初学者的学习计划。
                </div>
                <div className="ml-auto max-w-[80%] rounded-[24px] rounded-br-xl border border-[#d6e7ff] bg-[linear-gradient(180deg,rgba(235,243,255,0.95),rgba(223,236,255,0.82))] px-4 py-3 text-[12px] leading-6 text-[#17345b] shadow-[0_12px_24px_rgba(73,116,191,0.14)]">
                  已为你整理可用 Agent，并准备了工具调用、记忆管理和工作流编排的分阶段学习路径。
                </div>
                <div className="flex items-center gap-2 rounded-[22px] border border-white/72 bg-white/80 px-3 py-3">
                  <span className="h-9 w-9 rounded-2xl bg-[linear-gradient(180deg,#edf4ff,#d9e7ff)]" />
                  <div className="h-2.5 flex-1 rounded-full bg-[#e8eef8]">
                    <div className="h-2.5 w-[58%] rounded-full bg-[linear-gradient(90deg,#0f6cfd,#8ab2ff)]" />
                  </div>
                  <span className="rounded-full bg-[#eef4ff] px-2 py-1 text-[10px] font-medium text-[#4774b3]">
                    Sync
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
