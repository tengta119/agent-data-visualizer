export type ApiResponse<T> = {
  code?: string;
  info?: string;
  data?: T;
};

export type JsonPrimitive = string | number | boolean | null;

export type JsonValue =
  | JsonPrimitive
  | {
      [key: string]: JsonValue;
    }
  | JsonValue[];

export type AgentConfig = {
  agentId: string;
  agentName?: string;
  agentDesc?: string;
};

export type QueryAgentConfigListData = AgentConfig[];

export type CreateSessionRequest = {
  agentId: string;
  userId: string;
};

export type CreateSessionData = {
  sessionId: string;
};

export type ChatRequest = {
  agentId: string;
  userId: string;
  sessionId: string;
  message: string;
};

export type StructuredAgentReply = {
  user?: string;
  drawio?: string;
  type?: "user" | "drawio";
  content?: string;
};

export type ChatData = {
  content?: string | StructuredAgentReply;
  user?: string;
  drawio?: string;
  type?: "user" | "drawio";
};

export type ChatResult = {
  user: string;
  drawio: string | null;
};

export type ChatStreamMessageType = "log" | "result" | "done" | "error";

export type ChatStreamMessage = {
  type: ChatStreamMessageType;
  stage?: string;
  sessionId?: string;
  requestId?: string;
  content?: string;
  timestamp?: number;
};

export type AgentConfigTables = Record<string, JsonValue>;

export type CurrentAgentConfigData = {
  enabled: boolean;
  tables: AgentConfigTables;
};

export type UpdateAgentConfigRequest = {
  enabled: boolean;
  tables: AgentConfigTables;
};

export type LoginPayload = {
  user: string;
  ts?: number;
};
