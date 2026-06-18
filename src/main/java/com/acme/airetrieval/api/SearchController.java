package com.acme.airetrieval.api;

import com.acme.airetrieval.api.dto.AnswerContextRequest;
import com.acme.airetrieval.api.dto.SearchRequest;
import com.acme.airetrieval.api.dto.SearchResponse;
import com.acme.airetrieval.config.ApplicationProps;
import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.retrieve.RetrievalOrchestrator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SearchController {
    private final RetrievalOrchestrator retrieval;
    private final ApplicationProps props;

    public SearchController(RetrievalOrchestrator retrieval, ApplicationProps props) {
        this.retrieval = retrieval;
        this.props = props;
    }

    @PostMapping("/search")
    public SearchResponse search(@RequestBody SearchRequest request) throws Exception {
        int k = request.k() == null ? props.defaultSearchK() : Math.min(request.k(), props.maxSearchResults());
        SearchFilter filter = new SearchFilter(request.repo(), request.domain(), request.type(), request.path(), request.branch());
        var hits = "bm25".equalsIgnoreCase(request.mode())
            ? retrieval.bm25Only(request.query(), filter, k)
            : retrieval.hybrid(request.query(), filter, k);
        return new SearchResponse(hits, hits.size());
    }

    @PostMapping("/answer-context")
    public String answerContext(@RequestBody AnswerContextRequest request) throws Exception {
        return retrieval.answerContext(request.query(),
            new SearchFilter(request.repo(), request.domain(), request.type(), null, null),
            request.budgetTokens() == null ? 0 : request.budgetTokens());
    }
}
