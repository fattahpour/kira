package com.acme.airetrieval.mcp;

import com.acme.airetrieval.graph.GraphQueries;
import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.ingest.FullReindexService;
import com.acme.airetrieval.ingest.model.EndpointInfo;
import com.acme.airetrieval.retrieve.RetrievalOrchestrator;
import com.acme.airetrieval.retrieve.dto.BeanGraph;
import com.acme.airetrieval.retrieve.dto.ExpandedContext;
import com.acme.airetrieval.retrieve.dto.IndexStatus;
import com.acme.airetrieval.retrieve.dto.KafkaFlow;
import com.acme.airetrieval.retrieve.dto.SearchResult;
import com.acme.airetrieval.retrieve.dto.SpecImplReport;
import com.acme.airetrieval.retrieve.dto.SymbolListResult;
import com.acme.airetrieval.retrieve.dto.SymbolRef;
import com.acme.airetrieval.retrieve.dto.SymbolView;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@Component
public class McpTools {
    private static final int MAX_BODY_CHARS = 8000;
    private final RetrievalOrchestrator retrieval;
    private final GraphQueries graph;
    private final FullReindexService reindexService;
    private final String serverVersion;

    public McpTools(RetrievalOrchestrator retrieval, GraphQueries graph,
                    FullReindexService reindexService,
                    @Value("${spring.ai.mcp.server.version:0.1.0}") String serverVersion) {
        this.retrieval = retrieval;
        this.graph = graph;
        this.reindexService = reindexService;
        this.serverVersion = serverVersion;
    }

    @Tool(description = "Search code chunks by semantic + BM25 hybrid search with reranking")
    public SearchResult search_code(
        @ToolParam(description = "natural language or keyword query") String query,
        @ToolParam(description = "repository name filter, or null for all repos") String repo,
        @ToolParam(description = "branch filter, or null for all branches") String branch,
        @ToolParam(description = "max results to return") int k) {
        try {
            return SearchResult.ok(retrieval.hybridRerank(query, new SearchFilter(repo, "CODE", null, null, branch), k));
        } catch (Exception e) {
            return SearchResult.err(e.getMessage());
        }
    }

    @Tool(description = "Search knowledge/docs chunks by semantic + BM25 hybrid search with reranking")
    public SearchResult search_knowledge(
        @ToolParam(description = "natural language or keyword query") String query,
        @ToolParam(description = "repository name filter, or null for all repos") String repo,
        @ToolParam(description = "branch filter, or null for all branches") String branch,
        @ToolParam(description = "max results to return") int k) {
        try {
            return SearchResult.ok(retrieval.hybridRerank(query, new SearchFilter(repo, "KNOWLEDGE", null, null, branch), k));
        } catch (Exception e) {
            return SearchResult.err(e.getMessage());
        }
    }

    @Tool(description = "Semantic + BM25 hybrid search across both code and knowledge")
    public SearchResult semantic_search(
        @ToolParam(description = "natural language query") String query,
        @ToolParam(description = "repository name filter, or null for all repos") String repo,
        @ToolParam(description = "branch filter, or null for all branches") String branch,
        @ToolParam(description = "max results to return") int k) {
        try {
            return SearchResult.ok(retrieval.hybridRerank(query, new SearchFilter(repo, null, null, null, branch), k));
        } catch (Exception e) {
            return SearchResult.err(e.getMessage());
        }
    }

    @Tool(description = "Fast keyword BM25-only search, no embedding; good for exact class names, FQN fragments, identifiers")
    public SearchResult keyword_search(
        @ToolParam(description = "keyword or exact name query") String query,
        @ToolParam(description = "repository filter, or null for all repos") String repo,
        @ToolParam(description = "domain filter: CODE, KNOWLEDGE, or null for all") String domain,
        @ToolParam(description = "max results to return") int k) {
        try {
            return SearchResult.ok(retrieval.bm25Only(query, new SearchFilter(repo, domain, null, null, null), k));
        } catch (Exception e) {
            return SearchResult.err(e.getMessage());
        }
    }

    @Tool(description = "Retrieve reranked, compacted answer context within a token budget")
    public String answer_context(
        @ToolParam(description = "natural language query") String query,
        @ToolParam(description = "repository name filter, or null for all repos") String repo,
        @ToolParam(description = "max token budget for returned context") int budgetTokens) {
        try {
            return retrieval.answerContext(query, new SearchFilter(repo, null, null, null, null), budgetTokens);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Get symbol details: signature, javadoc, full body, callers, callees")
    public SymbolView get_symbol(@ToolParam(description = "fully qualified name") String fqn) {
        SymbolView view = graph.getSymbolView(fqn);
        if (view == null || view.body() != null) return view;
        try {
            String body = retrieval.findBodyByFqn(fqn).orElse(null);
            if (body == null) return view;
            if (body.length() > MAX_BODY_CHARS) {
                int boundary = body.lastIndexOf('\n', MAX_BODY_CHARS);
                body = body.substring(0, boundary > 0 ? boundary : MAX_BODY_CHARS) + "\n// ... (truncated)";
            }
            return new SymbolView(view.fqn(), view.signature(), view.javadoc(), body,
                view.callerSignatures(), view.calleeSignatures());
        } catch (Exception e) {
            return view;
        }
    }

    @Tool(description = "Discover symbols by partial class name, method name, or FQN fragment")
    public SymbolListResult find_symbol(
        @ToolParam(description = "partial class name, method name, or FQN fragment") String partialName,
        @ToolParam(description = "optional type filter: CLASS, METHOD, INTERFACE, ENDPOINT, or null for all") String type) {
        try {
            return SymbolListResult.ok(retrieval.findSymbols(partialName, type, 10));
        } catch (Exception e) {
            return SymbolListResult.err(e.getMessage());
        }
    }

    @Tool(description = "Discover symbols by name fragment, returning fqn/type/path only with no snippets")
    public SymbolListResult discover_symbols(
        @ToolParam(description = "partial class name, method name, or FQN fragment") String partialName,
        @ToolParam(description = "optional type filter: CLASS, METHOD, INTERFACE, ENDPOINT, or null for all") String type,
        @ToolParam(description = "max results to return") int k) {
        try {
            List<SymbolRef> refs = retrieval.findSymbols(partialName, type, k).stream()
                .map(ref -> new SymbolRef(ref.fqn(), null, ref.type(), ref.path()))
                .toList();
            return SymbolListResult.ok(refs);
        } catch (Exception e) {
            return SymbolListResult.err(e.getMessage());
        }
    }

    @Tool(description = "Get callers of a symbol up to a given depth")
    public List<String> get_callers(
        @ToolParam(description = "fully qualified name") String fqn,
        @ToolParam(description = "BFS depth, 1 = direct callers only") int depth) {
        return graph.getCallers(fqn, depth);
    }

    @Tool(description = "Get callees of a symbol up to a given depth")
    public List<String> get_callees(
        @ToolParam(description = "fully qualified name") String fqn,
        @ToolParam(description = "BFS depth, 1 = direct callees only") int depth) {
        return graph.getCallees(fqn, depth);
    }

    @Tool(description = "Get Kafka topic flow: producers and consumers for a topic")
    public KafkaFlow get_kafka_flow(@ToolParam(description = "Kafka topic name") String topic) {
        return graph.getKafkaFlow(topic);
    }

    @Tool(description = "Expand context by walking the code graph from seed FQNs up to N hops, returning related symbol signatures")
    public ExpandedContext expand_context(
        @ToolParam(description = "comma-separated fully qualified names to start from") String fqns,
        @ToolParam(description = "number of graph hops, 1 = direct neighbors only") int hops,
        @ToolParam(description = "max signatures to return, 0 = default 50") int maxResults) {
        List<String> seeds = Arrays.stream(fqns.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        int limit = maxResults <= 0 ? 50 : Math.min(maxResults, 200);
        List<String> signatures = graph.expandContext(seeds, hops, limit);
        return new ExpandedContext(seeds, hops, signatures);
    }

    @Tool(description = "Trigger a full reindex of a repository and return final indexed document count")
    public IndexStatus refresh_index(
        @ToolParam(description = "repository name") String repo,
        @ToolParam(description = "absolute path to the repository directory on disk") String repoDir) {
        try {
            var result = reindexService.reindex(repo, Path.of(repoDir), "local");
            return new IndexStatus(result.indexed(), serverVersion + " [reindex complete: " + repo + "]", null, false);
        } catch (Exception e) {
            return new IndexStatus(-1, serverVersion + " [ERROR: " + e.getMessage() + "]", null, false);
        }
    }

    @Tool(description = "Look up a REST endpoint by HTTP method and path")
    public EndpointInfo get_endpoint(
        @ToolParam(description = "HTTP method: GET, POST, PUT, DELETE, PATCH") String method,
        @ToolParam(description = "URL path, e.g. /api/v1/search") String path) {
        return graph.getEndpoint(method, path).orElse(null);
    }

    @Tool(description = "Get Spring bean dependency graph via constructor injection")
    public BeanGraph get_bean_graph(
        @ToolParam(description = "bean class simple name or partial FQN") String name,
        @ToolParam(description = "BFS depth for dependency traversal") int depth) {
        return graph.getBeanGraph(name, depth);
    }

    @Tool(description = "Find design documents related to a code symbol")
    public SearchResult get_design_for_symbol(
        @ToolParam(description = "fully qualified name of the code symbol") String fqn) {
        try {
            return SearchResult.ok(retrieval.getDesignDocs(fqn));
        } catch (Exception e) {
            return SearchResult.err(e.getMessage());
        }
    }

    @Tool(description = "Find code related to a documentation chunk")
    public SearchResult get_code_for_doc(
        @ToolParam(description = "document chunk id") String docId) {
        try {
            return SearchResult.ok(retrieval.getCodeForDoc(docId));
        } catch (Exception e) {
            return SearchResult.err(e.getMessage());
        }
    }

    @Tool(description = "Compare OpenAPI spec endpoints against implemented REST endpoints in the code graph")
    public SpecImplReport check_spec_vs_impl(
        @ToolParam(description = "repository name to scope the comparison") String repo) {
        try {
            return retrieval.checkSpecVsImpl(repo);
        } catch (Exception e) {
            return new SpecImplReport(repo, List.of(), List.of(), List.of(), 0, e.getMessage());
        }
    }

    @Tool(description = "Return number of indexed documents and server version")
    public IndexStatus index_status() {
        try {
            return new IndexStatus(retrieval.indexDocCount(), serverVersion, null, false);
        } catch (Exception e) {
            return new IndexStatus(-1, serverVersion + " [ERROR: " + e.getMessage() + "]", null, false);
        }
    }
}
