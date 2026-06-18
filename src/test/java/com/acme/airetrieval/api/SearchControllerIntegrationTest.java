package com.acme.airetrieval.api;

import com.acme.airetrieval.AiRetrievalApplication;
import com.acme.airetrieval.api.dto.SearchRequest;
import com.acme.airetrieval.api.dto.SearchResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AiRetrievalApplication.class, properties = {
    "kira.index-dir=${java.io.tmpdir}/kira-test-lucene",
    "kira.checkpoint-file=${java.io.tmpdir}/kira-test-checkpoint-${random.uuid}.json",
    "kira.embedding.dim=32",
    "spring.ai.mcp.server.stdio=false"
})
@AutoConfigureMockMvc
class SearchControllerIntegrationTest {
    @Autowired MockMvc mockMvc;

    @Test
    void contextLoads() {}

    @Test
    void searchResponse_hasTotal() {
        var fields = Arrays.stream(SearchResponse.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
        assertThat(fields).contains("total", "hits");
    }

    @Test
    void searchRequest_hasModeField() {
        var fields = Arrays.stream(SearchRequest.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
        assertThat(fields).contains("mode", "branch");
        assertThat(fields).doesNotContain("hybrid");
    }

    @Test
    void indexStatus_isEmptyOnFreshStart() throws Exception {
        mockMvc.perform(get("/api/v1/index/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.checkpoints").isMap())
            .andExpect(jsonPath("$.checkpoints").isEmpty())
            .andExpect(jsonPath("$.lastSync").isMap())
            .andExpect(jsonPath("$.lastSync").isEmpty());
    }
}
