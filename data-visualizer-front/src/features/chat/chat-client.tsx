"use client";

import Link from "next/link";
import {
  startTransition,
  useEffect,
  useEffectEvent,
  useMemo,
  useRef,
  useState,
} from "react";
import { useRouter } from "next/navigation";
import AgentDrawIoPanel from "@/src/components/agent-drawio-panel";
import {
  chatWithAgent,
  createAgentSession,
  isBackendUnavailableError,
  queryAgentConfigList,
} from "@/src/api/agent";
import { COOKIE_NAME, deleteCookieValue, formatTime } from "@/src/utils/cookie";
import type { AgentConfig, ChatResult } from "@/src/types/api";

type ChatBubble = {
  id: string;
  side: "user" | "agent";
  text: string;
  meta?: string;
};

type ChatBookmark = {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  userId: string;
  selectedAgentId: string;
  selectedAgentLabel: string;
  sessionId: string;
  bubbles: ChatBubble[];
  diagramXml: string | null;
  inputPlaceholder: string;
};

type ChatBookmarkStore = {
  version: 1;
  activeBookmarkId: string | null;
  bookmarks: ChatBookmark[];
};

type ChatClientProps = {
  apiBase: string;
  initialAgents: AgentConfig[];
  initialBackendIssue: string | null;
  userId: string;
  loginTs: number | null;
};

const DEFAULT_INPUT_PLACEHOLDER =
  "请输入问题，Ctrl/Command + Enter 发送，Enter 换行";
const BOOKMARK_STORE_VERSION = 1;

function createBubble(
  side: ChatBubble["side"],
  text: string,
  meta?: string,
): ChatBubble {
  return {
    id: `${side}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    side,
    text,
    meta,
  };
}

function buildInputPlaceholder(prompt: string) {
  const compactPrompt = prompt.replace(/\s+/g, " ").trim();
  if (!compactPrompt) {
    return DEFAULT_INPUT_PLACEHOLDER;
  }

  const shortPrompt =
    compactPrompt.length > 42
      ? `${compactPrompt.slice(0, 41)}…`
      : compactPrompt;

  return `请补充：${shortPrompt}`;
}

function buildBookmarkTitle(text: string) {
  const compactText = text.replace(/\s+/g, " ").trim();
  if (!compactText) {
    return `新对话 ${formatTime(Date.now())}`;
  }
  return compactText.length > 18 ? `${compactText.slice(0, 18)}…` : compactText;
}

function createBookmarkStorageKey(userId: string) {
  return `ai_agent_chat_bookmarks:${userId}`;
}

function safeParseJson<T>(text: string) {
  try {
    return JSON.parse(text) as T;
  } catch {
    return null;
  }
}

function base64ToUint8Array(base64: string) {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

async function tryInflate(bytes: Uint8Array, format: "deflate-raw" | "deflate") {
  const stream = new Blob([bytes]).stream().pipeThrough(
    new DecompressionStream(format),
  );
  const buffer = await new Response(stream).arrayBuffer();
  return new TextDecoder().decode(buffer);
}

async function expandCompressedMxfile(xml: string) {
  if (
    typeof DOMParser === "undefined" ||
    typeof XMLSerializer === "undefined" ||
    typeof DecompressionStream === "undefined"
  ) {
    return xml;
  }

  try {
    const doc = new DOMParser().parseFromString(xml, "text/xml");
    if (doc.querySelector("parsererror")) {
      return xml;
    }

    const diagramNodes = Array.from(doc.querySelectorAll("diagram"));
    for (const diagramNode of diagramNodes) {
      if (diagramNode.children.length > 0) continue;

      const rawText = (diagramNode.textContent || "").trim();
      if (!rawText || rawText.includes("<mxGraphModel")) continue;

      let inflated = "";
      const bytes = base64ToUint8Array(rawText);

      try {
        inflated = await tryInflate(bytes, "deflate-raw");
      } catch {
        try {
          inflated = await tryInflate(bytes, "deflate");
        } catch {
          inflated = "";
        }
      }

      if (!inflated) continue;

      let decoded = inflated;
      try {
        decoded = decodeURIComponent(inflated);
      } catch {
        decoded = inflated;
      }

      const innerDoc = new DOMParser().parseFromString(decoded, "text/xml");
      const innerGraphModel = innerDoc.querySelector("mxGraphModel");
      if (!innerGraphModel) continue;

      diagramNode.textContent = "";
      diagramNode.appendChild(doc.importNode(innerGraphModel, true));
    }

    return new XMLSerializer().serializeToString(doc);
  } catch {
    return xml;
  }
}

async function normalizeDrawIoXml(xml: string) {
  if (
    typeof DOMParser === "undefined" ||
    typeof XMLSerializer === "undefined"
  ) {
    return xml;
  }

  try {
    const expandedXml = await expandCompressedMxfile(xml);
    const doc = new DOMParser().parseFromString(expandedXml, "text/xml");
    if (doc.querySelector("parsererror")) {
      return expandedXml;
    }

    const graphModel = doc.querySelector("mxGraphModel");
    if (!graphModel) {
      return expandedXml;
    }

    const vertexGeometries = Array.from(
      doc.querySelectorAll('mxCell[vertex="1"] > mxGeometry'),
    );
    const edgePoints = Array.from(
      doc.querySelectorAll(
        'mxCell[edge="1"] mxGeometry mxPoint[x], mxCell[edge="1"] mxGeometry Array mxPoint[x]',
      ),
    );

    let minX = Number.POSITIVE_INFINITY;
    let minY = Number.POSITIVE_INFINITY;
    let maxX = Number.NEGATIVE_INFINITY;
    let maxY = Number.NEGATIVE_INFINITY;

    for (const geometry of vertexGeometries) {
      if (geometry.getAttribute("relative") === "1") continue;

      const x = Number(geometry.getAttribute("x") ?? "0");
      const y = Number(geometry.getAttribute("y") ?? "0");
      const width = Number(geometry.getAttribute("width") ?? "0");
      const height = Number(geometry.getAttribute("height") ?? "0");

      if (Number.isFinite(x) && Number.isFinite(y)) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x + Math.max(width, 0));
        maxY = Math.max(maxY, y + Math.max(height, 0));
      }
    }

    for (const point of edgePoints) {
      const x = Number(point.getAttribute("x") ?? "0");
      const y = Number(point.getAttribute("y") ?? "0");

      if (Number.isFinite(x) && Number.isFinite(y)) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
      }
    }

    if (!Number.isFinite(minX) || !Number.isFinite(minY)) {
      return expandedXml;
    }

    const margin = 48;
    const offsetX = margin - minX;
    const offsetY = margin - minY;

    for (const geometry of vertexGeometries) {
      if (geometry.getAttribute("relative") === "1") continue;

      const x = Number(geometry.getAttribute("x") ?? "0");
      const y = Number(geometry.getAttribute("y") ?? "0");

      if (Number.isFinite(x)) {
        geometry.setAttribute(
          "x",
          String(Math.round((x + offsetX) * 100) / 100),
        );
      }
      if (Number.isFinite(y)) {
        geometry.setAttribute(
          "y",
          String(Math.round((y + offsetY) * 100) / 100),
        );
      }
    }

    for (const point of edgePoints) {
      const x = Number(point.getAttribute("x") ?? "0");
      const y = Number(point.getAttribute("y") ?? "0");

      if (Number.isFinite(x)) {
        point.setAttribute(
          "x",
          String(Math.round((x + offsetX) * 100) / 100),
        );
      }
      if (Number.isFinite(y)) {
        point.setAttribute(
          "y",
          String(Math.round((y + offsetY) * 100) / 100),
        );
      }
    }

    const contentWidth = Math.max(720, Math.ceil(maxX - minX + margin * 2));
    const contentHeight = Math.max(540, Math.ceil(maxY - minY + margin * 2));

    graphModel.setAttribute("dx", "0");
    graphModel.setAttribute("dy", "0");
    graphModel.setAttribute("page", "1");
    graphModel.setAttribute("pageScale", "1");
    graphModel.setAttribute("pageWidth", String(contentWidth));
    graphModel.setAttribute("pageHeight", String(contentHeight));

    const rootGeometry = graphModel.querySelector(
      'root > mxCell[id="0"] > mxGeometry',
    );
    if (rootGeometry) {
      rootGeometry.setAttribute("x", "0");
      rootGeometry.setAttribute("y", "0");
      rootGeometry.setAttribute("width", String(contentWidth));
      rootGeometry.setAttribute("height", String(contentHeight));
    }

    return new XMLSerializer().serializeToString(doc);
  } catch {
    return xml;
  }
}

async function normalizeChatResult(result: ChatResult) {
  return {
    user: result.user.trim(),
    drawio: result.drawio ? await normalizeDrawIoXml(result.drawio) : null,
  };
}

function restoreConversationStateFromBookmark(
  bookmark: ChatBookmark,
  setSelectedAgentId: (value: string) => void,
  setSessionId: (value: string) => void,
  setBubbles: (value: ChatBubble[]) => void,
  setDiagramXml: (value: string | null) => void,
  setInputPlaceholder: (value: string) => void,
  setStatus: (value: string) => void,
  setStatusType: (value: "info" | "error") => void,
  setMessage: (value: string) => void,
) {
  setSelectedAgentId(bookmark.selectedAgentId);
  setSessionId(bookmark.sessionId);
  setBubbles(bookmark.bubbles);
  setDiagramXml(bookmark.diagramXml);
  setInputPlaceholder(bookmark.inputPlaceholder || DEFAULT_INPUT_PLACEHOLDER);
  setStatus("已恢复对话书签。");
  setStatusType("info");
  setMessage("");
}

export default function ChatClient({
  apiBase,
  initialAgents,
  initialBackendIssue,
  userId,
  loginTs,
}: ChatClientProps) {
  const router = useRouter();
  const chatRef = useRef<HTMLDivElement | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const [agents, setAgents] = useState(initialAgents);
  const [selectedAgentId, setSelectedAgentId] = useState(
    initialAgents[0]?.agentId ?? "",
  );
  const [sessionId, setSessionId] = useState("");
  const [message, setMessage] = useState("");
  const [status, setStatus] = useState(
    initialBackendIssue ? `智能体列表加载失败：${initialBackendIssue}` : "已加载智能体列表。",
  );
  const [statusType, setStatusType] = useState<"info" | "error">(
    initialBackendIssue ? "error" : "info",
  );
  const [sending, setSending] = useState(false);
  const [bubbles, setBubbles] = useState<ChatBubble[]>([]);
  const [backendIssue, setBackendIssue] = useState<string | null>(
    initialBackendIssue,
  );
  const [diagramXml, setDiagramXml] = useState<string | null>(null);
  const [inputPlaceholder, setInputPlaceholder] = useState(
    DEFAULT_INPUT_PLACEHOLDER,
  );
  const [bookmarks, setBookmarks] = useState<ChatBookmark[]>([]);
  const [activeBookmarkId, setActiveBookmarkId] = useState<string | null>(null);
  const [didHydrateBookmarks, setDidHydrateBookmarks] = useState(false);

  const quickPrompts = useMemo(
    () => [
      {
        label: "绘制 H5 端登录流程图",
        value:
          "请帮我绘制 H5 端登录流程图，要求包含：用户打开登录页、输入账号密码、图形验证码、短信验证码登录、登录成功后的 token 保存、异常提示、忘记密码入口，以及整体采用从上到下的清晰流程布局。",
      },
      {
        label: "绘制电商购物流程图",
        value:
          "请帮我绘制电商购物流程图，要求包含：浏览商品、加入购物车、确认订单、选择收货地址、选择支付方式、支付成功、订单发货、用户收货、售后处理，整体用业务流程图形式表达，节点层次清晰且避免连线交叉。",
      },
    ],
    [],
  );

  const selectedAgent = useMemo(
    () => agents.find((agent) => agent.agentId === selectedAgentId) ?? null,
    [agents, selectedAgentId],
  );

  const activeBookmark = useMemo(
    () => bookmarks.find((bookmark) => bookmark.id === activeBookmarkId) ?? null,
    [activeBookmarkId, bookmarks],
  );

  const latestUserBubble = useMemo(
    () => [...bubbles].reverse().find((bubble) => bubble.side === "user") ?? null,
    [bubbles],
  );
  const latestAgentBubble = useMemo(
    () => [...bubbles].reverse().find((bubble) => bubble.side === "agent") ?? null,
    [bubbles],
  );

  const bookmarkStorageKey = useMemo(
    () => createBookmarkStorageKey(userId),
    [userId],
  );

  const hydrateBookmarks = useEffectEvent(() => {
    if (typeof window === "undefined") return;

    const raw = window.localStorage.getItem(bookmarkStorageKey);
    const parsed = raw ? safeParseJson<ChatBookmarkStore>(raw) : null;
    if (!parsed || parsed.version !== BOOKMARK_STORE_VERSION) {
      setDidHydrateBookmarks(true);
      return;
    }

    const nextBookmarks = Array.isArray(parsed.bookmarks) ? parsed.bookmarks : [];
    setBookmarks(nextBookmarks);
    setActiveBookmarkId(parsed.activeBookmarkId ?? null);

    const bookmarkToRestore =
      nextBookmarks.find((bookmark) => bookmark.id === parsed.activeBookmarkId) ??
      nextBookmarks[0] ??
      null;

    if (bookmarkToRestore) {
      restoreConversationStateFromBookmark(
        bookmarkToRestore,
        setSelectedAgentId,
        setSessionId,
        setBubbles,
        setDiagramXml,
        setInputPlaceholder,
        setStatus,
        setStatusType,
        setMessage,
      );
      setActiveBookmarkId(bookmarkToRestore.id);
    }

    setDidHydrateBookmarks(true);
  });

  const syncActiveBookmark = useEffectEvent(() => {
    setBookmarks((current) => {
      const matched = current.find((bookmark) => bookmark.id === activeBookmarkId);
      if (!matched) return current;

      const nextTitle = buildBookmarkTitle(
        bubbles.find((bubble) => bubble.side === "user")?.text || matched.title || "",
      );
      const selectedAgentLabel = selectedAgent
        ? `${selectedAgent.agentName || selectedAgent.agentId}${
            selectedAgent.agentDesc ? ` - ${selectedAgent.agentDesc}` : ""
          }`
        : matched.selectedAgentLabel || "-";
      const hasChanged =
        matched.title !== nextTitle ||
        matched.selectedAgentId !== selectedAgentId ||
        matched.selectedAgentLabel !== selectedAgentLabel ||
        matched.sessionId !== sessionId ||
        matched.diagramXml !== diagramXml ||
        matched.inputPlaceholder !== inputPlaceholder ||
        JSON.stringify(matched.bubbles) !== JSON.stringify(bubbles);

      if (!hasChanged) {
        return current;
      }

      const updatedBookmark: ChatBookmark = {
        ...matched,
        title: nextTitle,
        updatedAt: Date.now(),
        selectedAgentId,
        selectedAgentLabel,
        sessionId,
        bubbles,
        diagramXml,
        inputPlaceholder,
      };

      return [
        updatedBookmark,
        ...current.filter((bookmark) => bookmark.id !== activeBookmarkId),
      ];
    });
  });

  useEffect(() => {
    const node = chatRef.current;
    if (!node) return;
    node.scrollTop = node.scrollHeight;
  }, [bubbles]);

  useEffect(() => {
    let cancelled = false;

    queueMicrotask(() => {
      if (!cancelled) {
        hydrateBookmarks();
      }
    });

    return () => {
      cancelled = true;
    };
  }, [bookmarkStorageKey]);

  useEffect(() => {
    if (!didHydrateBookmarks || typeof window === "undefined") return;

    const store: ChatBookmarkStore = {
      version: BOOKMARK_STORE_VERSION,
      activeBookmarkId,
      bookmarks,
    };
    window.localStorage.setItem(bookmarkStorageKey, JSON.stringify(store));
  }, [activeBookmarkId, bookmarkStorageKey, bookmarks, didHydrateBookmarks]);

  useEffect(() => {
    if (!activeBookmarkId) return;

    let cancelled = false;

    queueMicrotask(() => {
      if (!cancelled) {
        syncActiveBookmark();
      }
    });

    return () => {
      cancelled = true;
    };
  }, [
    activeBookmarkId,
    bubbles,
    diagramXml,
    inputPlaceholder,
    selectedAgent,
    selectedAgentId,
    sessionId,
  ]);

  function setStatusMessage(
    nextStatus: string,
    type: "info" | "error" = "info",
  ) {
    setStatus(nextStatus);
    setStatusType(type);
  }

  function resetConversationState(keepAgentId = true) {
    setSessionId("");
    setMessage("");
    setBubbles([]);
    setDiagramXml(null);
    setInputPlaceholder(DEFAULT_INPUT_PLACEHOLDER);
    setStatusMessage(
      initialBackendIssue ? `智能体列表加载失败：${initialBackendIssue}` : "已加载智能体列表。",
      initialBackendIssue ? "error" : "info",
    );
    if (!keepAgentId) {
      setSelectedAgentId(agents[0]?.agentId ?? "");
    }
  }

  function createBookmarkFromCurrentConversation(firstUserMessage: string) {
    const selectedAgentLabel = selectedAgent
      ? `${selectedAgent.agentName || selectedAgent.agentId}${
          selectedAgent.agentDesc ? ` - ${selectedAgent.agentDesc}` : ""
        }`
      : "-";

    const bookmarkId = `bookmark-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    const now = Date.now();
    const bookmark: ChatBookmark = {
      id: bookmarkId,
      title: buildBookmarkTitle(firstUserMessage),
      createdAt: now,
      updatedAt: now,
      userId,
      selectedAgentId,
      selectedAgentLabel,
      sessionId: "",
      bubbles: [],
      diagramXml: null,
      inputPlaceholder: DEFAULT_INPUT_PLACEHOLDER,
    };

    setBookmarks((current) => [bookmark, ...current]);
    setActiveBookmarkId(bookmarkId);
    return bookmarkId;
  }

  function handleNewConversation() {
    setActiveBookmarkId(null);
    resetConversationState(true);
    setStatusMessage("已创建新的空白对话。");
    textareaRef.current?.focus();
  }

  function handleSelectBookmark(bookmark: ChatBookmark) {
    setActiveBookmarkId(bookmark.id);
    restoreConversationStateFromBookmark(
      bookmark,
      setSelectedAgentId,
      setSessionId,
      setBubbles,
      setDiagramXml,
      setInputPlaceholder,
      setStatus,
      setStatusType,
      setMessage,
    );
  }

  function handleDeleteBookmark(bookmarkId: string) {
    setBookmarks((current) => current.filter((bookmark) => bookmark.id !== bookmarkId));

    if (bookmarkId !== activeBookmarkId) {
      return;
    }

    const remainingBookmarks = bookmarks.filter((bookmark) => bookmark.id !== bookmarkId);
    const nextBookmark = remainingBookmarks[0] ?? null;

    if (nextBookmark) {
      setActiveBookmarkId(nextBookmark.id);
      restoreConversationStateFromBookmark(
        nextBookmark,
        setSelectedAgentId,
        setSessionId,
        setBubbles,
        setDiagramXml,
        setInputPlaceholder,
        setStatus,
        setStatusType,
        setMessage,
      );
      return;
    }

    setActiveBookmarkId(null);
    resetConversationState(true);
    setStatusMessage("已删除当前书签。");
  }

  async function loadAgents() {
    try {
      const data = await queryAgentConfigList({ method: "GET" });
      setAgents(data);
      setSelectedAgentId((current) =>
        data.some((agent) => agent.agentId === current)
          ? current
          : (data[0]?.agentId ?? ""),
      );
      setBackendIssue(null);
      setStatusMessage("已加载智能体列表。");
    } catch (error) {
      const messageText =
        error instanceof Error ? error.message : "智能体列表加载失败";
      setAgents([]);
      setSelectedAgentId("");
      setStatusMessage(`智能体列表加载失败：${messageText}`, "error");
      if (isBackendUnavailableError(error)) {
        setBackendIssue(messageText);
      }
    }
  }

  function updateBubble(id: string, nextText: string, nextMeta?: string) {
    setBubbles((current) =>
      current.map((bubble) =>
        bubble.id === id
          ? { ...bubble, text: nextText, meta: nextMeta ?? bubble.meta }
          : bubble,
      ),
    );
  }

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setStatusMessage("");

    const trimmedMessage = message.trim();
    if (!selectedAgentId) {
      setStatusMessage("请先选择智能体。", "error");
      return;
    }
    if (!trimmedMessage) return;

    const nextBookmarkId =
      activeBookmarkId ??
      (bubbles.length === 0 ? createBookmarkFromCurrentConversation(trimmedMessage) : null);

    if (!activeBookmarkId && nextBookmarkId) {
      setActiveBookmarkId(nextBookmarkId);
    }

    const userBubble = createBubble("user", trimmedMessage, `userId=${userId}`);
    const agentBubble = createBubble("agent", "思考中…", `agentId=${selectedAgentId}`);
    setBubbles((current) => [...current, userBubble, agentBubble]);
    setMessage("");
    setSending(true);

    try {
      const nextSessionId =
        sessionId ||
        (await createAgentSession({
          agentId: selectedAgentId,
          userId,
        }));
      setSessionId(nextSessionId);
      updateBubble(
        agentBubble.id,
        "思考中…",
        `agentId=${selectedAgentId} · sessionId=${nextSessionId}`,
      );

      const rawReply = await chatWithAgent({
        agentId: selectedAgentId,
        userId,
        sessionId: nextSessionId,
        message: trimmedMessage,
      });
      const parsedReply = await normalizeChatResult(rawReply);

      if (parsedReply.drawio) {
        setDiagramXml(parsedReply.drawio);
      }

      const assistantText =
        parsedReply.user ||
        (parsedReply.drawio
          ? "图表已生成并同步到 draw.io 面板。"
          : "(空响应)");

      updateBubble(
        agentBubble.id,
        assistantText,
        parsedReply.drawio
          ? `agentId=${selectedAgentId} · sessionId=${nextSessionId} · draw.io 已更新`
          : `agentId=${selectedAgentId} · sessionId=${nextSessionId}`,
      );
      setInputPlaceholder(
        parsedReply.user && !parsedReply.drawio
          ? buildInputPlaceholder(parsedReply.user)
          : DEFAULT_INPUT_PLACEHOLDER,
      );
      setBackendIssue(null);
      setStatusMessage(
        parsedReply.user && !parsedReply.drawio
          ? "智能体需要你补充信息后再继续。"
          : parsedReply.drawio
            ? "已完成，draw.io 图表已同步更新。"
            : "已完成。",
      );
    } catch (error) {
      const messageText = error instanceof Error ? error.message : "请求失败";
      updateBubble(
        agentBubble.id,
        `请求失败：${messageText}`,
        "请检查 API_BASE / 服务端 CORS / 接口是否启动",
      );
      setStatusMessage(`失败：${messageText}`, "error");
      if (isBackendUnavailableError(error)) {
        setBackendIssue(messageText);
      }
    } finally {
      setSending(false);
    }
  }

  function handleLogout() {
    deleteCookieValue(COOKIE_NAME);
    startTransition(() => {
      router.replace("/login");
    });
  }

  function handleQuickPromptClick(value: string) {
    setMessage(value);
    setInputPlaceholder(DEFAULT_INPUT_PLACEHOLDER);
    textareaRef.current?.focus();
  }

  return (
    <div className="relative flex h-screen w-full flex-col overflow-hidden">
      <div className="pointer-events-none absolute left-[8%] top-6 h-48 w-48 rounded-full bg-[radial-gradient(circle,rgba(148,192,255,0.48),rgba(148,192,255,0))]" />
      <div className="pointer-events-none absolute bottom-8 right-[6%] h-64 w-64 rounded-full bg-[radial-gradient(circle,rgba(193,221,255,0.78),rgba(193,221,255,0))]" />

      <header className="sticky top-0 z-10 border-b border-white/58 bg-[rgba(247,250,255,0.62)] backdrop-blur-[26px]">
        <div className="flex w-full flex-wrap items-start justify-between gap-x-4 gap-y-3 px-3 py-3.5 md:px-4 md:py-4">
          <div className="flex min-w-0 items-start gap-3">
            <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-[18px] bg-[linear-gradient(180deg,#ffffff,#dceafe)] text-sm font-bold tracking-[0.08em] text-[#0f6cfd] shadow-[0_10px_24px_rgba(107,140,194,0.16)]">
              DV
            </div>
            <div className="min-w-0 pt-0.5">
              <strong className="block truncate text-sm leading-5 text-[#1a3455]">
                AI 智能体对话 By Ai Agent Scaffold - @小傅哥
              </strong>
              <span className="mt-0.5 block truncate text-xs leading-5 text-[var(--muted-soft)]">
                API: {apiBase}
              </span>
            </div>
          </div>

          <div className="flex flex-wrap items-start justify-end gap-2">
            <select
              value={selectedAgentId}
              onChange={(event) => {
                setSelectedAgentId(event.target.value);
              }}
              className="ring-focus fluent-field h-[56px] min-w-[220px] rounded-[18px] px-4 text-sm"
              aria-label="选择智能体"
            >
              <option value="">
                {agents.length ? "请选择智能体" : "加载中或暂无数据"}
              </option>
              {agents.map((agent) => (
                <option key={agent.agentId} value={agent.agentId}>
                  {agent.agentName || agent.agentId}
                  {agent.agentDesc ? ` - ${agent.agentDesc}` : ""}
                </option>
              ))}
            </select>

            <div className="surface-panel flex h-[56px] min-w-[210px] flex-col justify-center rounded-[18px] border border-white/82 px-4">
              <b className="block text-xs leading-4 text-[#213c61]">{userId}</b>
              <span className="mt-0.5 block text-xs leading-4 text-[var(--muted-soft)]">
                {loginTs ? `登录于 ${formatTime(loginTs)}` : "已登录"}
              </span>
            </div>

            <Link
              href="/agent-config"
              className="hover-lift fluent-secondary flex h-[56px] items-center justify-center rounded-[18px] px-5 text-sm font-semibold"
            >
              Agent 配置
            </Link>

            <button
              type="button"
              onClick={handleLogout}
              className="hover-lift fluent-secondary flex h-[56px] items-center justify-center self-start rounded-[18px] px-6 text-sm font-semibold"
            >
              退出
            </button>
          </div>
        </div>
      </header>

      <main className="flex min-h-0 flex-1 overflow-hidden px-2 py-3 md:px-3 md:py-4">
        <div className="grid h-full min-h-0 w-full min-w-0 gap-3 md:gap-4 lg:grid-cols-[270px_3fr_1fr]">
          <aside className="glass-panel hidden min-h-0 min-w-0 overflow-hidden rounded-[34px] lg:flex lg:flex-col">
            <div className="relative z-10 flex items-center justify-between border-b border-[var(--line)] px-4 py-4">
              <div>
                <p className="text-[11px] font-semibold uppercase tracking-[0.22em] text-[#5d7fad]">
                  Bookmarks
                </p>
                <h2 className="mt-1 text-sm font-semibold text-[#1f3657]">
                  对话书签
                </h2>
              </div>
              <button
                type="button"
                onClick={handleNewConversation}
                className="hover-lift fluent-secondary rounded-[18px] px-3 py-2 text-xs font-semibold"
              >
                新建
              </button>
            </div>

            <div className="scrollbar-subtle relative z-10 flex-1 overflow-auto px-3 py-3">
              {bookmarks.length === 0 ? (
                <div className="surface-panel rounded-[24px] border border-white/82 px-4 py-4">
                  <p className="text-xs leading-6 text-[var(--muted-soft)]">
                    新的对话在首次发送后会自动生成书签，并把消息、智能体、Session 和 draw.io 图表一起保存到浏览器本地。
                  </p>
                </div>
              ) : (
                <div className="space-y-2.5">
                  {bookmarks.map((bookmark) => (
                    <div
                      key={bookmark.id}
                      className={`surface-panel rounded-[24px] border px-3 py-3 transition ${
                        bookmark.id === activeBookmarkId
                          ? "border-[#bcd5ff] bg-[linear-gradient(180deg,rgba(237,245,255,0.96),rgba(227,239,255,0.86))]"
                          : "border-white/82 bg-[linear-gradient(180deg,rgba(255,255,255,0.9),rgba(246,249,255,0.78))]"
                      }`}
                    >
                      <div className="flex items-start gap-2">
                        <button
                          type="button"
                          onClick={() => handleSelectBookmark(bookmark)}
                          className="min-w-0 flex-1 text-left"
                        >
                          <div className="truncate text-sm font-semibold text-[#1f3657]">
                            {bookmark.title}
                          </div>
                          <div className="mt-1 truncate text-[11px] text-[#6b82a0]">
                            {bookmark.selectedAgentLabel || "未选择智能体"}
                          </div>
                          <div className="mt-2 text-[11px] text-[var(--muted-soft)]">
                            {formatTime(bookmark.updatedAt)}
                          </div>
                        </button>
                        <button
                          type="button"
                          onClick={() => handleDeleteBookmark(bookmark.id)}
                          className="rounded-[14px] border border-white/84 bg-white/72 px-2.5 py-1.5 text-[11px] font-semibold text-[#6a7f9b]"
                        >
                          删除
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </aside>

          <AgentDrawIoPanel
            userId={userId}
            sessionId={sessionId}
            agentLabel={
              selectedAgent
                ? `${selectedAgent.agentName || selectedAgent.agentId}${
                    selectedAgent.agentDesc ? ` - ${selectedAgent.agentDesc}` : ""
                  }`
                : "-"
            }
            latestUserMessage={latestUserBubble?.text}
            latestAgentMessage={latestAgentBubble?.text}
            latestAgentMeta={latestAgentBubble?.meta || status}
            diagramXml={diagramXml}
          />

          <section className="glass-panel flex min-h-0 min-w-0 flex-col overflow-hidden rounded-[34px]">
            <div className="border-b border-[var(--line)] px-5 py-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <h1 className="text-lg font-semibold tracking-tight text-[#17304d]">
                    智能体控制中枢
                  </h1>
                  <p className="mt-1 text-sm text-[var(--muted-soft)]">
                    当前书签会同步保存对话内容、智能体信息、Session 和 draw.io 图表
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  {[
                    selectedAgent ? selectedAgent.agentName || selectedAgent.agentId : "未选择智能体",
                    activeBookmark ? activeBookmark.title : "未保存对话",
                  ].map((item) => (
                    <span
                      key={item}
                      className="rounded-full border border-white/86 bg-white/74 px-3 py-1.5 text-[11px] font-medium text-[#4a6f9f]"
                    >
                      {item}
                    </span>
                  ))}
                </div>
              </div>
            </div>

            <div
              ref={chatRef}
              className="scrollbar-subtle flex-1 overflow-auto px-4 py-5 md:px-5"
            >
              {bubbles.length === 0 ? (
                <div className="surface-panel rounded-[28px] border border-white/84 px-5 py-5">
                  <p className="text-xs font-semibold uppercase tracking-[0.22em] text-[#6e84a1]">
                    Ready
                  </p>
                  <p className="mt-3 text-sm leading-7 text-[#49617f]">
                    选择智能体后开始对话。首次发送会自动生成一个新的对话书签；后续消息、图表与 Session 信息都会绑定到该书签，并保存在浏览器本地。
                  </p>
                </div>
              ) : null}

              <div className="mt-4 space-y-3">
                {bubbles.map((bubble) => (
                  <div
                    key={bubble.id}
                    className={`flex ${
                      bubble.side === "user" ? "justify-start" : "justify-end"
                    }`}
                  >
                    <div
                      className={`max-w-[min(74%,920px)] rounded-[26px] px-4 py-3 text-sm leading-7 whitespace-pre-wrap shadow-[0_12px_24px_rgba(100,124,160,0.08)] ${
                        bubble.side === "user"
                          ? "border border-white/86 bg-[linear-gradient(180deg,rgba(255,255,255,0.92),rgba(246,249,255,0.84))] text-[#24415f]"
                          : "border border-[#d9e8ff] bg-[linear-gradient(180deg,rgba(235,243,255,0.96),rgba(223,236,255,0.84))] text-[#17345b]"
                      }`}
                    >
                      <div>{bubble.text}</div>
                      {bubble.meta ? (
                        <div className="mt-2 text-[11px] text-[#7387a4]">
                          {bubble.meta}
                        </div>
                      ) : null}
                    </div>
                  </div>
                ))}
              </div>
            </div>

            <div
              className={`min-h-5 px-5 pb-3 text-xs ${
                statusType === "error"
                  ? "text-[var(--danger)]"
                  : "text-[var(--muted-soft)]"
              }`}
            >
              {status}
            </div>

            <form
              onSubmit={handleSubmit}
              className="border-t border-[var(--line)] bg-[rgba(247,250,255,0.48)] p-4"
            >
              {bubbles.length === 0 ? (
                <div className="mb-3 flex flex-wrap gap-2">
                  {quickPrompts.map((prompt) => (
                    <button
                      key={prompt.label}
                      type="button"
                      onClick={() => handleQuickPromptClick(prompt.value)}
                      className="hover-lift rounded-full border border-white/86 bg-white/76 px-3 py-2 text-xs font-medium text-[#4a6f9f] shadow-[inset_0_1px_0_rgba(255,255,255,0.96)]"
                    >
                      {prompt.label}
                    </button>
                  ))}
                </div>
              ) : null}

              <div className="flex items-end gap-3">
                <textarea
                  ref={textareaRef}
                  value={message}
                  onChange={(event) => setMessage(event.target.value)}
                  onKeyDown={(event) => {
                    if (
                      event.key === "Enter" &&
                      (event.ctrlKey || event.metaKey) &&
                      !event.shiftKey
                    ) {
                      event.preventDefault();
                      event.currentTarget.form?.requestSubmit();
                    }
                  }}
                  disabled={sending}
                  placeholder={inputPlaceholder}
                  className="ring-focus fluent-field min-h-[88px] max-h-[220px] flex-1 resize-none rounded-[22px] px-4 py-3 text-sm leading-7 disabled:cursor-not-allowed disabled:opacity-70"
                />
                <button
                  type="submit"
                  disabled={sending}
                  className="hover-lift fluent-primary cursor-pointer rounded-[22px] px-5 py-3 font-semibold disabled:cursor-not-allowed disabled:opacity-70"
                >
                  {sending ? "发送中…" : "发送"}
                </button>
              </div>
            </form>
          </section>
        </div>
      </main>

      {backendIssue ? (
        <div className="fixed inset-0 z-30 flex items-center justify-center bg-[rgba(225,234,246,0.45)] px-4 backdrop-blur-md">
          <div className="glass-panel w-full max-w-xl overflow-hidden rounded-[32px]">
            <div className="flex items-center justify-between gap-3 border-b border-[var(--line)] px-5 py-4">
              <h3 className="text-sm font-semibold text-[#183352]">服务端接口不可用</h3>
              <button
                type="button"
                onClick={() => setBackendIssue(null)}
                className="hover-lift fluent-secondary cursor-pointer rounded-[18px] px-4 py-2 text-sm font-semibold"
              >
                关闭
              </button>
            </div>
            <div className="space-y-3 px-5 py-4">
              <p className="text-sm leading-7 text-[var(--muted)]">
                当前页面需要访问服务端接口。请先启动服务端，然后点击“重试”。如果服务端已启动但仍不可用，请检查前端环境变量
                `NEXT_PUBLIC_API_BASE` 是否指向正确地址。
              </p>
              <pre className="overflow-x-auto rounded-[22px] border border-white/82 bg-white/74 px-4 py-3 text-xs leading-6 text-[#2e4b74]">
{`API_BASE: ${apiBase}
curl -s ${apiBase}/api/v1/query_ai_agent_config_list

错误：${backendIssue}`}
              </pre>
            </div>
            <div className="flex justify-end gap-3 px-5 pb-5">
              <button
                type="button"
                onClick={async () => {
                  setBackendIssue(null);
                  setStatusMessage("正在重试连接服务端…");
                  await loadAgents();
                }}
                className="hover-lift fluent-secondary cursor-pointer rounded-[18px] px-4 py-2 text-sm font-semibold"
              >
                我已启动，重试
              </button>
              <button
                type="button"
                onClick={() => setBackendIssue(null)}
                className="hover-lift fluent-primary cursor-pointer rounded-[18px] px-4 py-2 text-sm font-semibold"
              >
                知道了
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
