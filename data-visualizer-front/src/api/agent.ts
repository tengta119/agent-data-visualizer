import { AGENT_API_PATHS, buildApiUrl } from "@/src/config/api-config";
import type {
  ApiResponse,
  ChatData,
  ChatRequest,
  CreateSessionData,
  CreateSessionRequest,
  QueryAgentConfigListData,
} from "@/src/types/api";

function safeParseJson(text: string) {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

export async function requestJson<T>(path: string, options?: RequestInit) {
  const response = await fetch(buildApiUrl(path), {
    headers: {
      "Content-Type": "application/json",
      ...(options?.headers ?? {}),
    },
    ...options,
  });

  const raw = await response.text();
  const parsed = safeParseJson(raw);

  if (!response.ok) {
    const message =
      parsed && typeof parsed === "object" && "info" in parsed
        ? String(parsed.info)
        : raw || `HTTP ${response.status}`;
    throw new Error(message);
  }

  return (parsed ?? {}) as ApiResponse<T>;
}

export function ensureSuccess<T>(response: ApiResponse<T>) {
  if (!response) {
    throw new Error("接口无返回");
  }

  if (response.code && response.code !== "0000") {
    throw new Error(response.info || `接口失败：${response.code}`);
  }

  return response.data;
}

export function isBackendUnavailableError(error: unknown) {
  const message = error instanceof Error ? error.message : String(error || "");
  return (
    message.includes("Failed to fetch") ||
    message.includes("NetworkError") ||
    message.includes("Load failed") ||
    message.includes("ECONNREFUSED") ||
    message.includes("fetch") ||
    message.includes("CORS")
  );
}

export async function queryAgentConfigList(options?: RequestInit) {
  const response = await requestJson<QueryAgentConfigListData>(
    AGENT_API_PATHS.queryAgentConfigList,
    options,
  );
  return ensureSuccess(response) ?? [];
}

export async function createAgentSession(payload: CreateSessionRequest) {
  const response = await requestJson<CreateSessionData>(
    AGENT_API_PATHS.createSession,
    {
      method: "POST",
      body: JSON.stringify(payload),
    },
  );
  const data = ensureSuccess(response);
  const sessionId = data?.sessionId?.trim();

  if (!sessionId) {
    throw new Error("创建会话失败：未返回 sessionId");
  }

  return sessionId;
}

export async function chatWithAgent(payload: ChatRequest) {
  const response = await requestJson<ChatData>(AGENT_API_PATHS.chat, {
    method: "POST",
    body: JSON.stringify(payload),
  });

  return ensureSuccess(response)?.content ?? "";
}
