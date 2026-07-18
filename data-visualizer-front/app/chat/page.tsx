import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import ChatClient from "./chat-client";
import { API_BASE, requestJson, type AgentConfig } from "@/lib/agent-api";
import { COOKIE_NAME, parseLoginPayload } from "@/lib/agent-auth";

export default async function ChatPage() {
  const cookieStore = await cookies();
  const payload = parseLoginPayload(cookieStore.get(COOKIE_NAME)?.value);

  if (!payload?.user) {
    redirect("/login");
  }

  let initialAgents: AgentConfig[] = [];
  let initialBackendIssue: string | null = null;

  try {
    const response = await requestJson<AgentConfig[]>(
      "/api/v1/query_ai_agent_config_list",
      {
        method: "GET",
        cache: "no-store",
      },
    );
    initialAgents = response.data ?? [];
  } catch (error) {
    initialBackendIssue =
      error instanceof Error ? error.message : "智能体列表加载失败";
  }

  return (
    <ChatClient
      apiBase={API_BASE}
      initialAgents={initialAgents}
      initialBackendIssue={initialBackendIssue}
      userId={payload.user}
      loginTs={payload.ts ?? null}
    />
  );
}
