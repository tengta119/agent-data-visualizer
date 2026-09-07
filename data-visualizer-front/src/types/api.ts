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

export type ChatStreamMessageType =
  | "log"
  | "result"
  | "done"
  | "error"
  | "approval_required"
  | "approval_resolved";

export type ChatStreamMessage = {
  type: ChatStreamMessageType;
  stage?: string;
  sessionId?: string;
  requestId?: string;
  content?: string;
  timestamp?: number;
  /** 审批标识；approval_required / approval_resolved 消息携带 */
  approvalId?: string;
  /** 命令类型；local/remote */
  commandType?: string;
  /** 目标远程终端名称 */
  hostName?: string;
  /** 审批原因 */
  reason?: string;
  /** 审批过期时间戳（毫秒） */
  expiresAt?: number;
};

export type ApprovalDecision = "approve_once" | "reject";

export type ChatStreamApprovalRequest = {
  approvalId: string;
  decision: ApprovalDecision;
};

export type ChatStreamApprovalData = {
  requestId?: string;
  approvalId?: string;
  status?: string;
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
