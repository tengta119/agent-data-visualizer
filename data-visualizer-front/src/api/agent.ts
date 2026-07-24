import { AGENT_API_PATHS, buildApiUrl } from "@/src/config/api-config";
import type {
  ApiResponse,
  ChatData,
  ChatResult,
  ChatRequest,
  CreateSessionData,
  CreateSessionRequest,
  QueryAgentConfigListData,
  StructuredAgentReply,
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

function normalizeMxGraphModel(graphModelXml: string) {
  return `<mxfile host="app.diagrams.net" modified="${new Date().toISOString()}" agent="Codex" version="26.0.11">
  <diagram id="agent-workbench" name="Agent Workbench">
${graphModelXml}
  </diagram>
</mxfile>`;
}

function extractDrawIoXml(text: string) {
  const mxfileMatch = text.match(/<mxfile[\s\S]*?<\/mxfile>/i);
  if (mxfileMatch?.[0]) {
    return mxfileMatch[0].trim();
  }

  const graphModelMatch = text.match(/<mxGraphModel[\s\S]*?<\/mxGraphModel>/i);
  if (graphModelMatch?.[0]) {
    return normalizeMxGraphModel(graphModelMatch[0].trim());
  }

  return null;
}

function readStructuredReply(value: unknown): ChatResult | null {
  if (!value || typeof value !== "object") return null;

  const candidate = value as StructuredAgentReply;
  const user =
    typeof candidate.user === "string" ? candidate.user.trim() : "";
  const drawio =
    typeof candidate.drawio === "string" ? candidate.drawio.trim() : "";

  if (user || drawio) {
    return {
      user,
      drawio: drawio || null,
    };
  }

  if (candidate.type === "user" && typeof candidate.content === "string") {
    return {
      user: candidate.content.trim(),
      drawio: null,
    };
  }

  if (candidate.type === "drawio" && typeof candidate.content === "string") {
    return {
      user: "",
      drawio: candidate.content.trim() || null,
    };
  }

  return null;
}

function normalizeChatResponse(data: ChatData | null | undefined): ChatResult {
  if (!data) {
    return {
      user: "",
      drawio: null,
    };
  }

  const directStructured = readStructuredReply(data);
  if (directStructured) {
    return directStructured;
  }

  const content = data.content;
  if (typeof content === "object" && content !== null) {
    return (
      readStructuredReply(content) ?? {
        user: "",
        drawio: null,
      }
    );
  }

  if (typeof content === "string") {
    const trimmed = content.trim();
    const parsed = safeParseJson(trimmed);
    const parsedStructured = readStructuredReply(parsed);
    if (parsedStructured) {
      return parsedStructured;
    }

    const drawioXml = extractDrawIoXml(trimmed);
    if (drawioXml) {
      return {
        user: "",
        drawio: drawioXml,
      };
    }

    return {
      user: trimmed,
      drawio: null,
    };
  }

  return {
    user: "",
    drawio: null,
  };
}

export async function chatWithAgent(payload: ChatRequest) {
  const response = await requestJson<ChatData>(AGENT_API_PATHS.chat, {
    method: "POST",
    body: JSON.stringify(payload),
  });

  return normalizeChatResponse(ensureSuccess(response));
}
