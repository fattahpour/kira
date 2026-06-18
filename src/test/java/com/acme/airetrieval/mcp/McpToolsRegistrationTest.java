package com.acme.airetrieval.mcp;

import com.acme.airetrieval.AiRetrievalApplication;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AiRetrievalApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "kira.index-dir=${java.io.tmpdir}/kira-mcp-reg-test",
        "kira.embedding.dim=32",
        "spring.ai.mcp.server.stdio=false"
})
class McpToolsRegistrationTest {

    @Autowired
    ToolCallbackProvider toolCallbackProvider;

    @Test
    void toolProvider_exposesAllMcpTools() {
        var names = Arrays.stream(toolCallbackProvider.getToolCallbacks())
            .map(tc -> tc.getToolDefinition().name())
            .toList();
        assertThat(names).containsExactlyInAnyOrder(
            "search_code", "search_knowledge", "semantic_search", "answer_context",
            "keyword_search", "get_symbol", "get_callers", "get_callees", "get_kafka_flow",
            "expand_context", "find_symbol", "discover_symbols", "refresh_index", "get_endpoint",
            "get_bean_graph", "get_design_for_symbol", "get_code_for_doc",
            "check_spec_vs_impl", "index_status"
        );
    }
}
