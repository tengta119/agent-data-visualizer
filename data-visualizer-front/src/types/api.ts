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

export type ChatData = {
  content: string;
};

export type LoginPayload = {
  user: string;
  ts?: number;
};
