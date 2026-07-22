export const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE?.trim() || "http://127.0.0.1:8091";

export const AGENT_API_PATHS = {
  queryAgentConfigList: "/api/v1/query_ai_agent_config_list",
  createSession: "/api/v1/create_session",
  chat: "/api/v1/chat",
} as const;

export function buildApiUrl(path: string) {
  return `${API_BASE}${path}`;
}
