import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import ChatClient from "./chat-client";
import { queryAgentConfigList } from "@/src/api/agent";
import { API_BASE } from "@/src/config/api-config";
import { COOKIE_NAME, parseLoginPayload } from "@/src/utils/cookie";
import type { AgentConfig } from "@/src/types/api";

export default async function ChatPage() {
  const cookieStore = await cookies();
  const payload = parseLoginPayload(cookieStore.get(COOKIE_NAME)?.value);

  if (!payload?.user) {
    redirect("/login");
  }

  let initialAgents: AgentConfig[] = [];
  let initialBackendIssue: string | null = null;

  try {
    initialAgents = await queryAgentConfigList({
      method: "GET",
      cache: "no-store",
    });
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
