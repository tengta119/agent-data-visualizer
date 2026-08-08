"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { DrawIoEmbed, type DrawIoEmbedRef } from "react-drawio";

type AgentDrawIoPanelProps = {
  userId: string;
  sessionId: string;
  agentLabel: string;
  latestUserMessage?: string;
  latestAgentMessage?: string;
  latestAgentMeta?: string;
  diagramXml?: string | null;
};

type DrawIoLoadOptions = Parameters<DrawIoEmbedRef["load"]>[0] & {
  fit?: 1;
  border?: number;
  maxFitScale?: number;
};

function escapeXml(value: string) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;")
    .replaceAll("\n", "&#xa;");
}

function shortenText(value: string, limit: number) {
  const trimmed = value.replace(/\s+/g, " ").trim();
  if (!trimmed) return "-";
  if (trimmed.length <= limit) return trimmed;
  return `${trimmed.slice(0, limit - 1)}…`;
}

function buildFallbackDiagramXml({
  userId,
  sessionId,
  agentLabel,
  latestUserMessage,
  latestAgentMessage,
  latestAgentMeta,
}: AgentDrawIoPanelProps) {
  const safeUserId = escapeXml(userId || "guest");
  const safeSession = escapeXml(sessionId || "Waiting for first message");
  const safeAgent = escapeXml(agentLabel || "No agent selected");
  const safeQuestion = escapeXml(shortenText(latestUserMessage || "", 120));
  const safeReply = escapeXml(shortenText(latestAgentMessage || "", 220));
  const safeMeta = escapeXml(shortenText(latestAgentMeta || "", 120));

  return `<mxfile host="app.diagrams.net" modified="2026-07-22T00:00:00.000Z" agent="Codex" version="26.0.11">
  <diagram id="agent-workbench" name="Agent Workbench">
    <mxGraphModel dx="1360" dy="900" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="1280" pageHeight="960" math="0" shadow="0">
      <root>
        <mxCell id="0"/>
        <mxCell id="1" parent="0"/>

        <mxCell id="title" value="AI Agent Workbench" style="text;html=1;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;fontSize=22;fontStyle=1;fontColor=#17345B;" vertex="1" parent="1">
          <mxGeometry x="36" y="22" width="280" height="30" as="geometry"/>
        </mxCell>
        <mxCell id="subtitle" value="Session + draw.io live context" style="text;html=1;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;fontSize=11;fontColor=#6F8099;" vertex="1" parent="1">
          <mxGeometry x="36" y="50" width="240" height="20" as="geometry"/>
        </mxCell>

        <mxCell id="user" value="Current User&#xa;${safeUserId}" style="rounded=1;whiteSpace=wrap;html=1;arcSize=18;fillColor=#FFFFFF;gradientColor=#F3F7FF;strokeColor=#D7E6FF;fontColor=#1E3C63;fontSize=13;fontStyle=1;spacing=10;" vertex="1" parent="1">
          <mxGeometry x="36" y="96" width="164" height="74" as="geometry"/>
        </mxCell>
        <mxCell id="session" value="Session Context&#xa;${safeSession}" style="rounded=1;whiteSpace=wrap;html=1;arcSize=18;fillColor=#F9FBFF;gradientColor=#EAF2FF;strokeColor=#D7E6FF;fontColor=#21456E;fontSize=13;fontStyle=1;spacing=10;" vertex="1" parent="1">
          <mxGeometry x="228" y="96" width="224" height="74" as="geometry"/>
        </mxCell>
        <mxCell id="agent" value="Selected Agent&#xa;${safeAgent}" style="rounded=1;whiteSpace=wrap;html=1;arcSize=18;fillColor=#EAF3FF;gradientColor=#D8E8FF;strokeColor=#A9CBFF;fontColor=#123B73;fontSize=13;fontStyle=1;spacing=10;" vertex="1" parent="1">
          <mxGeometry x="136" y="208" width="220" height="82" as="geometry"/>
        </mxCell>

        <mxCell id="questionTitle" value="Latest Prompt" style="text;html=1;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;fontSize=13;fontStyle=1;fontColor=#33557F;" vertex="1" parent="1">
          <mxGeometry x="36" y="336" width="140" height="20" as="geometry"/>
        </mxCell>
        <mxCell id="question" value="${safeQuestion}" style="rounded=1;whiteSpace=wrap;html=1;arcSize=20;fillColor=#F8FBFF;gradientColor=#EEF5FF;strokeColor=#D2E2FA;fontColor=#21456E;fontSize=12;spacing=12;align=left;verticalAlign=top;" vertex="1" parent="1">
          <mxGeometry x="36" y="364" width="416" height="112" as="geometry"/>
        </mxCell>

        <mxCell id="replyTitle" value="Latest Reply" style="text;html=1;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;fontSize=13;fontStyle=1;fontColor=#33557F;" vertex="1" parent="1">
          <mxGeometry x="36" y="510" width="140" height="20" as="geometry"/>
        </mxCell>
        <mxCell id="reply" value="${safeReply}" style="rounded=1;whiteSpace=wrap;html=1;arcSize=20;fillColor=#EAF4FF;gradientColor=#DDEBFF;strokeColor=#A8CAFF;fontColor=#123B73;fontSize=12;spacing=12;align=left;verticalAlign=top;" vertex="1" parent="1">
          <mxGeometry x="36" y="538" width="416" height="154" as="geometry"/>
        </mxCell>

        <mxCell id="metaTitle" value="Reply Meta" style="text;html=1;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;fontSize=13;fontStyle=1;fontColor=#33557F;" vertex="1" parent="1">
          <mxGeometry x="36" y="720" width="120" height="20" as="geometry"/>
        </mxCell>
        <mxCell id="meta" value="${safeMeta}" style="rounded=1;whiteSpace=wrap;html=1;arcSize=18;fillColor=#FFFFFF;gradientColor=#F4F8FF;strokeColor=#D9E5F8;fontColor=#47617D;fontSize=11;spacing=10;align=left;verticalAlign=top;" vertex="1" parent="1">
          <mxGeometry x="36" y="748" width="416" height="68" as="geometry"/>
        </mxCell>

        <mxCell id="edge-user-session" value="" style="edgeStyle=orthogonalEdgeStyle;rounded=1;jettySize=auto;html=1;strokeColor=#8FB6F8;strokeWidth=2;endArrow=blockThin;endFill=1;" edge="1" parent="1" source="user" target="session">
          <mxGeometry relative="1" as="geometry"/>
        </mxCell>
        <mxCell id="edge-session-agent" value="" style="edgeStyle=orthogonalEdgeStyle;rounded=1;jettySize=auto;html=1;strokeColor=#8FB6F8;strokeWidth=2;endArrow=blockThin;endFill=1;" edge="1" parent="1" source="session" target="agent">
          <mxGeometry relative="1" as="geometry"/>
        </mxCell>
        <mxCell id="edge-agent-question" value="" style="edgeStyle=orthogonalEdgeStyle;rounded=1;jettySize=auto;html=1;strokeColor=#8FB6F8;strokeWidth=2;endArrow=blockThin;endFill=1;" edge="1" parent="1" source="agent" target="question">
          <mxGeometry relative="1" as="geometry"/>
        </mxCell>
        <mxCell id="edge-question-reply" value="" style="edgeStyle=orthogonalEdgeStyle;rounded=1;jettySize=auto;html=1;strokeColor=#8FB6F8;strokeWidth=2;endArrow=blockThin;endFill=1;" edge="1" parent="1" source="question" target="reply">
          <mxGeometry relative="1" as="geometry"/>
        </mxCell>
        <mxCell id="edge-reply-meta" value="" style="edgeStyle=orthogonalEdgeStyle;rounded=1;jettySize=auto;html=1;strokeColor=#8FB6F8;strokeWidth=2;endArrow=blockThin;endFill=1;" edge="1" parent="1" source="reply" target="meta">
          <mxGeometry relative="1" as="geometry"/>
        </mxCell>
      </root>
    </mxGraphModel>
  </diagram>
</mxfile>`;
}

export default function AgentDrawIoPanel(props: AgentDrawIoPanelProps) {
  const drawioRef = useRef<DrawIoEmbedRef>(null);
  const [ready, setReady] = useState(false);
  const xml = useMemo(
    () => props.diagramXml?.trim() || buildFallbackDiagramXml(props),
    [props],
  );
  const embedKey = useMemo(
    () =>
      props.diagramXml?.trim()
        ? `diagram-${props.diagramXml.trim().slice(0, 64)}`
        : "diagram-fallback",
    [props.diagramXml],
  );

  useEffect(() => {
    if (!ready || !drawioRef.current) return;
    // AI 坐标可能从很大的偏移量开始，导入后强制适配并居中，避免图形停在左上角且过小。
    const loadOptions: DrawIoLoadOptions = {
      xml,
      fit: 1,
      border: 32,
      maxFitScale: 1,
    };
    drawioRef.current.load(loadOptions);
  }, [ready, xml]);

  return (
    <aside className="glass-panel hidden min-h-0 min-w-0 overflow-hidden rounded-[34px] lg:flex lg:flex-col">
      <div className="relative z-10 flex items-center justify-between border-b border-[var(--line)] px-5 py-4">
        <div>
          <p className="text-[11px] font-semibold uppercase tracking-[0.22em] text-[#5d7fad]">
            Diagram
          </p>
          <h2 className="mt-1 text-sm font-semibold text-[#1f3657]">
            智能体流程图
          </h2>
        </div>
        <div className="rounded-full border border-white/84 bg-white/74 px-3 py-1 text-[11px] font-medium text-[#4970a5]">
          react-drawio
        </div>
      </div>

      <div className="relative z-10 flex-1 p-4">
        <div className="fluent-accent-border h-full overflow-hidden rounded-[28px] p-2">
          <div className="surface-panel h-full overflow-hidden rounded-[24px] border border-white/84">
            <DrawIoEmbed
              key={embedKey}
              ref={drawioRef}
              xml={xml}
              onLoad={() => setReady(true)}
              urlParameters={{
                ui: "simple",
                spin: true,
                libraries: false,
                noSaveBtn: true,
                noExitBtn: true,
                modified: false,
                keepmodified: false,
                grid: true,
              }}
              configuration={{
                defaultFonts: ["Segoe UI"],
              }}
            />
          </div>
        </div>
      </div>
    </aside>
  );
}
