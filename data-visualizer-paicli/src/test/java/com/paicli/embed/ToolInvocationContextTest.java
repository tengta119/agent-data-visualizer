package com.paicli.embed;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ToolInvocationContextTest {
    @Test
    void hostShellBatchExecutesInOrderOnCallingThreadWithRequestContext() throws Exception {
        ToolRegistry tools = ToolRegistry.restricted();
        Thread caller = Thread.currentThread();
        java.util.ArrayList<String> seen = new java.util.ArrayList<>();
        tools.registerMcpToolOutput(new McpToolDescriptor("ShellExecutor", "execute", "mcp__ShellExecutor__execute",
                "Host command requiring approval", JsonNodeFactory.instance.objectNode()), args -> {
            assertEquals(caller, Thread.currentThread());
            assertEquals("request-a", ToolInvocationContext.current());
            seen.add(args);
            return ToolOutput.text(args);
        });
        List<ToolRegistry.ToolInvocation> calls = List.of(
                new ToolRegistry.ToolInvocation("one", "mcp__ShellExecutor__execute", "first"),
                new ToolRegistry.ToolInvocation("two", "mcp__ShellExecutor__execute", "second"));
        var results = ToolInvocationContext.call("request-a", () -> tools.executeTools(calls));
        assertEquals(List.of("first", "second"), seen);
        assertEquals(List.of("one", "two"), results.stream().map(ToolRegistry.ToolExecutionResult::id).toList());
        assertNull(ToolInvocationContext.current());
    }

    @Test
    void parallelToolsSeeOnlyTheirOwnExplicitRequestContext() throws Exception {
        ToolRegistry tools = ToolRegistry.restricted();
        tools.registerMcpToolOutput(new McpToolDescriptor("test", "identity", "mcp__test__identity",
                "Request identity", JsonNodeFactory.instance.objectNode()),
                ignored -> ToolOutput.text(String.valueOf(ToolInvocationContext.current())));
        List<ToolRegistry.ToolInvocation> calls = List.of(
                new ToolRegistry.ToolInvocation("one", "mcp__test__identity", "{}"),
                new ToolRegistry.ToolInvocation("two", "mcp__test__identity", "{}"));

        var first = ToolInvocationContext.call("request-a", () -> tools.executeTools(calls));
        var second = ToolInvocationContext.call("request-b", () -> tools.executeTools(calls));

        assertEquals(List.of("request-a", "request-a"), first.stream().map(ToolRegistry.ToolExecutionResult::result).toList());
        assertEquals(List.of("request-b", "request-b"), second.stream().map(ToolRegistry.ToolExecutionResult::result).toList());
        assertNull(ToolInvocationContext.current());
        assertFalse(tools.executeToolOutput("execute_command", "{}").successful());
    }
}
