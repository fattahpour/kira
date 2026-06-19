package com.acme.airetrieval.mcp;

import com.acme.airetrieval.graph.GraphQueries;
import com.acme.airetrieval.index.IndexMonitorService;
import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.index.model.SearchHit;
import com.acme.airetrieval.ingest.FullReindexService;
import com.acme.airetrieval.ingest.model.EndpointInfo;
import com.acme.airetrieval.retrieve.RetrievalOrchestrator;
import com.acme.airetrieval.retrieve.dto.BeanGraph;
import com.acme.airetrieval.retrieve.dto.ExpandedContext;
import com.acme.airetrieval.retrieve.dto.IndexStatus;
import com.acme.airetrieval.retrieve.dto.SearchResult;
import com.acme.airetrieval.retrieve.dto.SpecImplReport;
import com.acme.airetrieval.retrieve.dto.SymbolListResult;
import com.acme.airetrieval.retrieve.dto.SymbolRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpToolsTest {

    RetrievalOrchestrator retrieval;
    GraphQueries graph;
    FullReindexService reindexService;
    IndexMonitorService indexMonitorService;
    McpTools tools;

    @BeforeEach
    void setup() {
        retrieval = mock(RetrievalOrchestrator.class);
        graph = mock(GraphQueries.class);
        reindexService = mock(FullReindexService.class);
        indexMonitorService = mock(IndexMonitorService.class);
        tools = new McpTools(retrieval, graph, reindexService, indexMonitorService, "0.1.0-test");
    }

    @Test
    void index_status_returns_doc_count() throws Exception {
        when(indexMonitorService.buildStatus("0.1.0-test"))
            .thenReturn(new IndexStatus(42, "0.1.0-test", List.of(), false));
        IndexStatus status = tools.index_status();
        assertThat(status.totalDocs()).isEqualTo(42);
        assertThat(status.serverVersion()).isEqualTo("0.1.0-test");
    }

    @Test
    void index_status_returns_minus_one_on_error() throws Exception {
        when(indexMonitorService.buildStatus("0.1.0-test")).thenThrow(new RuntimeException("disk error"));
        IndexStatus status = tools.index_status();
        assertThat(status.totalDocs()).isEqualTo(-1);
    }

    @Test
    void search_code_returnsSearchResultWithHits() throws Exception {
        var hit = mock(SearchHit.class);
        when(retrieval.hybridRerank(eq("foo"), any(SearchFilter.class), eq(5))).thenReturn(List.of(hit));
        SearchResult result = tools.search_code("foo", null, null, 5);
        assertThat(result.hits()).hasSize(1);
        assertThat(result.error()).isNull();
    }

    @Test
    void search_code_returnsErrorSearchResultOnException() throws Exception {
        when(retrieval.hybridRerank(anyString(), any(), anyInt())).thenThrow(new RuntimeException("oops"));
        SearchResult result = tools.search_code("query", null, null, 5);
        assertThat(result.isError()).isTrue();
        assertThat(result.error()).contains("oops");
        assertThat(result.hits()).isEmpty();
    }

    @Test
    void search_knowledge_usesRerankedSearch() throws Exception {
        when(retrieval.hybridRerank(eq("bar"), any(SearchFilter.class), eq(3))).thenReturn(List.of());
        SearchResult result = tools.search_knowledge("bar", null, null, 3);
        assertThat(result.isError()).isFalse();
        verify(retrieval).hybridRerank(eq("bar"), any(SearchFilter.class), eq(3));
    }

    @Test
    void keyword_search_returnsBm25Results() throws Exception {
        when(retrieval.bm25Only(eq("PaymentService"), any(SearchFilter.class), eq(5))).thenReturn(List.of());
        var result = tools.keyword_search("PaymentService", null, null, 5);
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        verify(retrieval).bm25Only(eq("PaymentService"), any(SearchFilter.class), eq(5));
    }

    @Test
    void find_symbol_delegatesToRetrieval() throws Exception {
        when(retrieval.findSymbols("PaymentService", null, 10))
            .thenReturn(List.of(new SymbolRef("com.acme.PaymentService", "class PaymentService", "CLASS", "PaymentService.java")));
        var result = tools.find_symbol("PaymentService", null);
        assertThat(result.symbols()).hasSize(1);
        assertThat(result.symbols().get(0).fqn()).isEqualTo("com.acme.PaymentService");
    }

    @Test
    void find_symbol_returnsSymbolListResultWithSymbols() throws Exception {
        when(retrieval.findSymbols("PaymentService", null, 10))
            .thenReturn(List.of(new SymbolRef("com.acme.PaymentService", "class PaymentService", "CLASS", "PaymentService.java")));
        SymbolListResult result = tools.find_symbol("PaymentService", null);
        assertThat(result.symbols()).hasSize(1);
        assertThat(result.error()).isNull();
        assertThat(result.isError()).isFalse();
    }

    @Test
    void find_symbol_returnsErrorResultOnException() throws Exception {
        when(retrieval.findSymbols(anyString(), any(), anyInt()))
            .thenThrow(new java.io.IOException("index locked"));
        var result = tools.find_symbol("Foo", null);
        assertThat(result.isError()).isTrue();
        assertThat(result.error()).contains("index locked");
        assertThat(result.symbols()).isEmpty();
    }

    @Test
    void discover_symbols_snippetIsNull() throws Exception {
        when(retrieval.findSymbols("Service", null, 10))
            .thenReturn(List.of(new SymbolRef("com.acme.PaymentService", "class PaymentService", "CLASS", "PaymentService.java")));
        var result = tools.discover_symbols("Service", null, 10);
        assertThat(result).isNotNull();
        assertThat(result.isError()).isFalse();
        assertThat(result.symbols()).allMatch(ref -> ref.snippet() == null);
    }

    @Test
    void expand_context_delegates_to_graph() {
        when(graph.expandContext(List.of("com.acme.Foo", "com.acme.Bar"), 2, 50))
            .thenReturn(List.of("void baz()"));
        var result = tools.expand_context("com.acme.Foo, com.acme.Bar", 2, 0);
        assertThat(result.signatures()).containsExactly("void baz()");
        assertThat(result.hops()).isEqualTo(2);
        assertThat(result.seedFqns()).containsExactly("com.acme.Foo", "com.acme.Bar");
    }

    @Test
    void get_endpoint_delegatesToGraph() {
        var endpointInfo = new EndpointInfo("POST", "/api/pay", null, "com.acme.PaymentController#pay()");
        when(graph.getEndpoint("POST", "/api/pay")).thenReturn(Optional.of(endpointInfo));
        var result = tools.get_endpoint("POST", "/api/pay");
        assertThat(result).isNotNull();
        assertThat(result.method()).isEqualTo("POST");
        assertThat(result.path()).isEqualTo("/api/pay");
    }

    @Test
    void get_endpoint_returnsNullForUnknown() {
        when(graph.getEndpoint(anyString(), anyString())).thenReturn(Optional.empty());
        assertThat(tools.get_endpoint("DELETE", "/unknown")).isNull();
    }

    @Test
    void get_bean_graph_delegatesToGraph() {
        var beanGraph = new BeanGraph("com.acme.OrderService", List.of());
        when(graph.getBeanGraph("OrderService", 2)).thenReturn(beanGraph);
        var result = tools.get_bean_graph("OrderService", 2);
        assertThat(result.root()).isEqualTo("com.acme.OrderService");
    }

    @Test
    void get_design_for_symbol_delegatesToRetrieval() throws Exception {
        when(retrieval.getDesignDocs("com.acme.PaymentService")).thenReturn(List.of());
        var result = tools.get_design_for_symbol("com.acme.PaymentService");
        assertThat(result).isNotNull();
        verify(retrieval).getDesignDocs("com.acme.PaymentService");
    }

    @Test
    void get_code_for_doc_delegatesToRetrieval() throws Exception {
        when(retrieval.getCodeForDoc("doc#section1")).thenReturn(List.of());
        var result = tools.get_code_for_doc("doc#section1");
        assertThat(result).isNotNull();
        verify(retrieval).getCodeForDoc("doc#section1");
    }

    @Test
    void check_spec_vs_impl_delegatesToRetrieval() throws Exception {
        var report = new SpecImplReport("myrepo", List.of(), List.of(), List.of(), 0, null);
        when(retrieval.checkSpecVsImpl("myrepo")).thenReturn(report);
        var result = tools.check_spec_vs_impl("myrepo");
        assertThat(result.repo()).isEqualTo("myrepo");
        assertThat(result.error()).isNull();
        assertThat(result.total()).isZero();
    }

    @Test
    void check_spec_vs_impl_returnsErrorOnException() throws Exception {
        when(retrieval.checkSpecVsImpl("myrepo"))
            .thenThrow(new java.io.IOException("searcher closed"));
        var result = tools.check_spec_vs_impl("myrepo");
        assertThat(result.repo()).isEqualTo("myrepo");
        assertThat(result.error()).isNotNull();
        assertThat(result.error()).contains("searcher closed");
        assertThat(result.unimplemented()).isEmpty();
        assertThat(result.undocumented()).isEmpty();
        assertThat(result.matched()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void getSymbol_bodyIsCappedAt8000Chars() throws Exception {
        var field = McpTools.class.getDeclaredField("MAX_BODY_CHARS");
        field.setAccessible(true);
        assertThat(field.get(null)).isEqualTo(8000);
    }
}
