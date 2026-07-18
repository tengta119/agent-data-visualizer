export const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE?.trim() || "http://127.0.0.1:8091";

export type ApiResponse<T> = {
  code?: string;
  info?: string;
  data?: T;
};

export type AgentConfig = {
  agentId: string;
  agentName?: string;
  agentDesc?: string;
};

function safeParseJson(text: string) {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

export async function requestJson<T>(path: string, options?: RequestInit) {
  const response = await fetch(`${API_BASE}${path}`, {
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
    message.includes("fetch") ||
    message.includes("CORS")
  );
}
