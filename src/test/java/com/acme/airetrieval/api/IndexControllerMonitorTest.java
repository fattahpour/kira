package com.acme.airetrieval.api;

import com.acme.airetrieval.index.IndexMonitorService;
import com.acme.airetrieval.retrieve.dto.IndexStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IndexController.class)
class IndexControllerMonitorTest {

    @Autowired MockMvc mockMvc;

    @MockBean IndexMonitorService indexMonitorService;
    @MockBean com.acme.airetrieval.ingest.IndexService indexService;
    @MockBean com.acme.airetrieval.ingest.FullReindexService fullReindexService;
    @MockBean com.acme.airetrieval.ingest.BranchSyncScheduler syncScheduler;

    @Test
    void monitorEndpointReturnsStatus() throws Exception {
        when(indexMonitorService.buildStatus(any())).thenReturn(
            new IndexStatus(42, "0.1.0", java.util.List.of(), false));

        mockMvc.perform(get("/api/v1/index/monitor"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalDocs").value(42))
            .andExpect(jsonPath("$.serverVersion").value("0.1.0"))
            .andExpect(jsonPath("$.anyIndexing").value(false));
    }
}
