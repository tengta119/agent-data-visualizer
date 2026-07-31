import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import AgentConfigClient from "./agent-config-client";
import { queryCurrentAgentConfig } from "@/src/api/agent";
import { API_BASE } from "@/src/config/api-config";
import { COOKIE_NAME, parseLoginPayload } from "@/src/utils/cookie";
import type { CurrentAgentConfigData } from "@/src/types/api";

export default async function AgentConfigPage() {
  const cookieStore = await cookies();
  const payload = parseLoginPayload(cookieStore.get(COOKIE_NAME)?.value);

  if (!payload?.user) {
    redirect("/login");
  }

  let initialConfig: CurrentAgentConfigData = {
    enabled: false,
    tables: {},
  };
  let initialBackendIssue: string | null = null;

  try {
    initialConfig = await queryCurrentAgentConfig({
      method: "GET",
      cache: "no-store",
    });
  } catch (error) {
    initialBackendIssue =
      error instanceof Error ? error.message : "Agent 配置加载失败";
  }

  return (
    <AgentConfigClient
      apiBase={API_BASE}
      initialBackendIssue={initialBackendIssue}
      initialConfig={initialConfig}
      userId={payload.user}
      loginTs={payload.ts ?? null}
    />
  );
}
