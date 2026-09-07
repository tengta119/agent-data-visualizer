import {
  AGENT_API_PATHS,
  buildApiUrl,
  chatStreamApprovalPath,
} from "@/src/config/api-config";
import type {
  ApiResponse,
  AgentConfigTables,
  ChatData,
  ChatResult,
  ChatRequest,
  ChatStreamApprovalData,
  ChatStreamApprovalRequest,
  ChatStreamMessage,
  CurrentAgentConfigData,
  CreateSessionData,
  CreateSessionRequest,
  QueryAgentConfigListData,
  StructuredAgentReply,
  UpdateAgentConfigRequest,
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

function consumeJsonMessages(source: string) {
  const messages: ChatStreamMessage[] = [];
  let startIndex = -1;
  let depth = 0;
  let inString = false;
  let escaped = false;
  let lastConsumedIndex = 0;

  for (let index = 0; index < source.length; index += 1) {
    const char = source[index];

    if (startIndex < 0) {
      if (/\s/.test(char)) {
        lastConsumedIndex = index + 1;
        continue;
      }

      if (char === "{") {
        startIndex = index;
        depth = 1;
        inString = false;
        escaped = false;
      } else {
        lastConsumedIndex = index + 1;
      }
      continue;
    }

    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (char === "\\") {
        escaped = true;
      } else if (char === '"') {
        inString = false;
      }
      continue;
    }

    if (char === '"') {
      inString = true;
      continue;
    }

    if (char === "{") {
      depth += 1;
      continue;
    }

    if (char !== "}") {
      continue;
    }

    depth -= 1;
    if (depth > 0) {
      continue;
    }

    const rawMessage = source.slice(startIndex, index + 1);
    const parsed = safeParseJson(rawMessage);
    if (parsed && typeof parsed === "object" && "type" in parsed) {
      messages.push(parsed as ChatStreamMessage);
    }

    startIndex = -1;
    depth = 0;
    inString = false;
    escaped = false;
    lastConsumedIndex = index + 1;
  }

  return {
    messages,
    rest: startIndex >= 0 ? source.slice(startIndex) : source.slice(lastConsumedIndex),
  };
}

export async function queryAgentConfigList(options?: RequestInit) {
  const response = await requestJson<QueryAgentConfigListData>(
    AGENT_API_PATHS.queryAgentConfigList,
    options,
  );
  return ensureSuccess(response) ?? [];
}

function normalizeAgentConfigTables(value: unknown): AgentConfigTables {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return {};
  }

  return value as AgentConfigTables;
}

function normalizeCurrentAgentConfig(
  data: CurrentAgentConfigData | null | undefined,
): CurrentAgentConfigData {
  return {
    enabled: Boolean(data?.enabled),
    tables: normalizeAgentConfigTables(data?.tables),
  };
}

export async function queryCurrentAgentConfig(options?: RequestInit) {
  const response = await requestJson<CurrentAgentConfigData>(
    AGENT_API_PATHS.queryCurrentAgentConfig,
    options,
  );

  return normalizeCurrentAgentConfig(ensureSuccess(response));
}

export async function updateAgentConfig(payload: UpdateAgentConfigRequest) {
  const response = await requestJson<CurrentAgentConfigData>(
    AGENT_API_PATHS.updateAgentConfig,
    {
      method: "POST",
      body: JSON.stringify(payload),
    },
  );

  return normalizeCurrentAgentConfig(ensureSuccess(response));
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

export function extractDrawIoXml(text: string) {
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

export async function streamChatWithAgent(
  payload: ChatRequest,
  options: {
    signal?: AbortSignal;
    onMessage: (message: ChatStreamMessage) => void;
  },
) {
  const response = await fetch(buildApiUrl(AGENT_API_PATHS.chatStream), {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
    cache: "no-store",
    signal: options.signal,
  });

  if (!response.ok) {
    const raw = await response.text();
    const parsed = safeParseJson(raw);
    const message =
      parsed && typeof parsed === "object" && "info" in parsed
        ? String(parsed.info)
        : raw || `HTTP ${response.status}`;
    throw new Error(message);
  }

  if (!response.body) {
    throw new Error("当前环境不支持流式读取响应");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const parsed = consumeJsonMessages(buffer);
    buffer = parsed.rest;

    for (const message of parsed.messages) {
      options.onMessage(message);
    }
  }

  buffer += decoder.decode();
  const trailing = consumeJsonMessages(buffer);
  for (const message of trailing.messages) {
    options.onMessage(message);
  }
}

export async function submitChatStreamApproval(
  requestId: string,
  payload: ChatStreamApprovalRequest,
) {
  const response = await requestJson<ChatStreamApprovalData>(
    chatStreamApprovalPath(requestId),
    {
      method: "POST",
      body: JSON.stringify(payload),
    },
  );

  return ensureSuccess(response);
}
