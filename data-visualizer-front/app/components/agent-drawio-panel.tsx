"use client";

import { useMemo } from "react";
import { DrawIoEmbed } from "react-drawio";

type AgentDrawIoPanelProps = {
  userId: string;
  sessionId: string;
  agentLabel: string;
};

function escapeXml(value: string) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;");
}

function buildDiagramXml({
  userId,
  sessionId,
  agentLabel,
}: AgentDrawIoPanelProps) {
  const safeUserId = escapeXml(userId || "guest");
  const safeSession = escapeXml(sessionId || "Waiting for first message");
  const safeAgent = escapeXml(agentLabel || "No agent selected");

  return `<mxfile host="app.diagrams.net" modified="2026-07-18T00:00:00.000Z" agent="Codex" version="26.0.11">
  <diagram id="agent-workbench" name="Agent Workbench">
    <mxGraphModel dx="1280" dy="720" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="1169" pageHeight="827" math="0" shadow="0">
      <root>
        <mxCell id="0"/>
        <mxCell id="1" parent="0"/>
        <mxCell id="title" value="AI Agent Workbench" style="text;html=1;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;fontSize=22;fontStyle=1;fontColor=#17345B;" vertex="1" parent="1">
          <mxGeometry x="36" y="24" width="260" height="30" as="geometry"/>
        </mxCell>
        <mxCell id="subtitle" value="Fluent 2 command flow" style="text;html=1;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;fontSize=11;fontColor=#6F8099;" vertex="1" parent="1">
          <mxGeometry x="36" y="52" width="220" height="20" as="geometry"/>
        </mxCell>

        <mxCell id="user" value="Current User&#xa;${safeUserId}" style="rounded=1;whiteSpace=wrap;html=1;glass=0;shadow=0;arcSize=18;fillColor=#FFFFFF;gradientColor=#F3F7FF;strokeColor=#D7E6FF;fontColor=#1E3C63;fontSize=13;fontStyle=1;spacing=10;" vertex="1" parent="1">
          <mxGeometry x="36" y="104" width="152" height="74" as="geometry"/>
        </mxCell>
        <mxCell id="session" value="Session Context&#xa;${safeSession}" style="rounded=1;whiteSpace=wrap;html=1;glass=0;shadow=0;arcSize=18;fillColor=#F9FBFF;gradientColor=#EAF2FF;strokeColor=#D7E6FF;fontColor=#21456E;fontSize=13;fontStyle=1;spacing=10;" vertex="1" parent="1">
          <mxGeometry x="218" y="104" width="188" height="74" as="geometry"/>
        </mxCell>
        <mxCell id="agent" value="Selected Agent&#xa;${safeAgent}" style="rounded=1;whiteSpace=wrap;html=1;glass=0;shadow=0;arcSize=18;fillColor=#EAF3FF;gradientColor=#D8E8FF;strokeColor=#A9CBFF;fontColor=#123B73;fontSize=13;fontStyle=1;spacing=10;" vertex="1" parent="1">
          <mxGeometry x="138" y="226" width="200" height="82" as="geometry"/>
        </mxCell>
        <mxCell id="tools" value="MCP Tool Chain&#xa;HTTP / SSE / Local Tool" style="rounded=1;whiteSpace=wrap;html=1;glass=0;shadow=0;arcSize=18;fillColor=#F6FAFF;gradientColor=#E7F0FF;strokeColor=#C9DCF8;fontColor=#21456E;fontSize=13;fontStyle=1;spacing=10;" vertex="1" parent="1">
          <mxGeometry x="36" y="360" width="180" height="82" as="geometry"/>
        </mxCell>
        <mxCell id="workflow" value="Orchestration Layer&#xa;Prompt + Memory + Routing" style="rounded=1;whiteSpace=wrap;html=1;glass=0;shadow=0;arcSize=18;fillColor=#F6FAFF;gradientColor=#E7F0FF;strokeColor=#C9DCF8;fontColor=#21456E;fontSize=13;fontStyle=1;spacing=10;" vertex="1" parent="1">
          <mxGeometry x="238" y="360" width="170" height="82" as="geometry"/>
        </mxCell>
        <mxCell id="response" value="Response Panel&#xa;Stable, system-like feedback" style="rounded=1;whiteSpace=wrap;html=1;glass=0;shadow=0;arcSize=18;fillColor=#EAF4FF;gradientColor=#DDEBFF;strokeColor=#A8CAFF;fontColor=#123B73;fontSize=13;fontStyle=1;spacing=10;" vertex="1" parent="1">
          <mxGeometry x="138" y="494" width="202" height="82" as="geometry"/>
        </mxCell>

        <mxCell id="edge-user-session" value="" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeColor=#8FB6F8;strokeWidth=2;endArrow=blockThin;endFill=1;" edge="1" parent="1" source="user" target="session">
          <mxGeometry relative="1" as="geometry"/>
        </mxCell>
        <mxCell id="edge-session-agent" value="" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeColor=#8FB6F8;strokeWidth=2;endArrow=blockThin;endFill=1;" edge="1" parent="1" source="session" target="agent">
          <mxGeometry relative="1" as="geometry"/>
        </mxCell>
        <mxCell id="edge-agent-tools" value="" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeColor=#8FB6F8;strokeWidth=2;endArrow=blockThin;endFill=1;" edge="1" parent="1" source="agent" target="tools">
          <mxGeometry relative="1" as="geometry"/>
        </mxCell>
        <mxCell id="edge-agent-workflow" value="" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeColor=#8FB6F8;strokeWidth=2;endArrow=blockThin;endFill=1;" edge="1" parent="1" source="agent" target="workflow">
          <mxGeometry relative="1" as="geometry"/>
        </mxCell>
        <mxCell id="edge-tools-response" value="" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeColor=#8FB6F8;strokeWidth=2;endArrow=blockThin;endFill=1;" edge="1" parent="1" source="tools" target="response">
          <mxGeometry relative="1" as="geometry"/>
        </mxCell>
        <mxCell id="edge-workflow-response" value="" style="edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;strokeColor=#8FB6F8;strokeWidth=2;endArrow=blockThin;endFill=1;" edge="1" parent="1" source="workflow" target="response">
          <mxGeometry relative="1" as="geometry"/>
        </mxCell>
      </root>
    </mxGraphModel>
  </diagram>
</mxfile>`;
}

export default function AgentDrawIoPanel(props: AgentDrawIoPanelProps) {
  const xml = useMemo(() => buildDiagramXml(props), [props]);

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
              xml={xml}
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
