package com.acme.airetrieval.ingest.parser;

import com.acme.airetrieval.graph.model.GraphEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiSpecParserTest {
    private static final String PETSTORE_YAML = """
        openapi: "3.0.0"
        info:
          title: Petstore
          version: "1.0"
        paths:
          /pets:
            get:
              operationId: listPets
              summary: List all pets
              responses:
                '200':
                  description: ok
            post:
              operationId: createPet
              summary: Create a pet
              responses:
                '201':
                  description: created
          /pets/{petId}:
            get:
              operationId: showPetById
              summary: Info for a specific pet
              responses:
                '200':
                  description: ok
        """;

    @Test
    void parse_emitsOpenApiOpChunks() {
        var result = new ApiSpecParser().parse("myrepo", "api/petstore.yaml", "abc123", PETSTORE_YAML);
        assertThat(result.chunks()).hasSize(3);
        assertThat(result.chunks()).allMatch(c -> "OPENAPI_OP".equals(c.type()));
    }

    @Test
    void parse_fqnIsMethodPlusPath() {
        var result = new ApiSpecParser().parse("myrepo", "api/petstore.yaml", "abc123", PETSTORE_YAML);
        assertThat(result.chunks()).anyMatch(c -> "GET /pets".equals(c.fqn()));
    }

    @Test
    void parse_emitsEndpointGraphNodes() {
        var result = new ApiSpecParser().parse("myrepo", "api/petstore.yaml", "abc123", PETSTORE_YAML);
        assertThat(result.events()).isNotEmpty();
    }

    @Test
    void parse_endpointNodesCarryRepoTag() {
        var result = new ApiSpecParser().parse("myrepo", "api/petstore.yaml", "abc123", PETSTORE_YAML);
        assertThat(result.events()).filteredOn(GraphEvent.NodeEvent.class::isInstance)
            .map(GraphEvent.NodeEvent.class::cast)
            .filteredOn(node -> "Endpoint".equals(node.label()))
            .allSatisfy(node -> assertThat(node.tags()).contains("REPO:myrepo"));
    }

    @Test
    void parse_chunkTextIncludesParametersAndResponses() {
        String yaml = """
            openapi: "3.0.0"
            info:
              title: Payment API
              version: "1.0"
            paths:
              /payments:
                post:
                  operationId: createPayment
                  summary: Create a payment
                  parameters:
                    - name: idempotencyKey
                      in: header
                      description: Idempotency key for deduplication
                  requestBody:
                    description: Payment details including amount and currency
                  responses:
                    '201':
                      description: Payment created successfully
            """;
        var result = new ApiSpecParser().parse("repo", "payment.yaml", "sha", yaml);
        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().getFirst().text())
            .contains("idempotencyKey")
            .contains("Payment details including amount")
            .contains("Payment created successfully");
    }
}
