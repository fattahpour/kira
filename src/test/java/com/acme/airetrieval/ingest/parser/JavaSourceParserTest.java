package com.acme.airetrieval.ingest.parser;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import com.acme.airetrieval.ingest.model.Domain;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSourceParserTest {
    private final JavaSourceParser parser = new JavaSourceParser();

    @Test
    void parseClassMethodAndGraphEvents() {
        String source = """
            package com.acme;
            import org.springframework.stereotype.Service;
            @Service
            public class PaymentService {
              public void settle(String orderId) { markPaid(orderId); }
              void markPaid(String orderId) {}
            }
            """;
        var result = parser.parse("repo", "src/PaymentService.java", "sha", source);
        assertThat(result.chunks()).anySatisfy(c -> {
            assertThat(c.domain()).isEqualTo(Domain.CODE);
            assertThat(c.type()).isEqualTo("METHOD");
            assertThat(c.fqn()).isEqualTo("com.acme.PaymentService#settle(String)");
        });
        assertThat(result.events()).anySatisfy(e -> {
            assertThat(e).isInstanceOf(GraphEvent.NodeEvent.class);
            var node = (GraphEvent.NodeEvent) e;
            assertThat(node.id()).isEqualTo("com.acme.PaymentService");
            assertThat(node.tags()).contains("BEAN");
        });
        assertThat(result.events()).anySatisfy(e -> {
            assertThat(e).isInstanceOf(GraphEvent.EdgeEvent.class);
            assertThat(((GraphEvent.EdgeEvent) e).type()).isEqualTo(GraphEdge.EdgeType.CALLS);
        });
    }

    @Test
    void parseEndpointAnnotation() {
        String source = """
            package com.acme;
            import org.springframework.web.bind.annotation.*;
            @RestController class C { @PostMapping("/pay") void pay() {} }
            """;
        var result = parser.parse("repo", "src/C.java", "sha", source);
        assertThat(result.events()).anySatisfy(e -> {
            assertThat(e).isInstanceOf(GraphEvent.EdgeEvent.class);
            assertThat(((GraphEvent.EdgeEvent) e).type()).isEqualTo(GraphEdge.EdgeType.EXPOSES);
        });
    }

    @Test
    void parse_postMapping_emitsExposesEdgeWithPath() {
        String controller = """
            package com.acme;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class PaymentController {
                @PostMapping("/api/pay")
                public void pay() {}
            }
            """;
        var result = parser.parse("repo", "src/PaymentController.java", "sha1", controller);

        assertThat(result.events()).anySatisfy(event -> {
            assertThat(event).isInstanceOf(GraphEvent.EdgeEvent.class);
            var edge = (GraphEvent.EdgeEvent) event;
            assertThat(edge.type()).isEqualTo(GraphEdge.EdgeType.EXPOSES);
            assertThat(edge.to()).isEqualTo("POST /api/pay");
        });

        assertThat(result.events()).anySatisfy(event -> {
            assertThat(event).isInstanceOf(GraphEvent.NodeEvent.class);
            var node = (GraphEvent.NodeEvent) event;
            assertThat(node.id()).isEqualTo("POST /api/pay");
            assertThat(node.tags()).contains("ENDPOINT");
        });
    }

    @Test
    void parse_getMappingNoPath_usesSlashFallback() {
        String controller = """
            package com.acme;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class HomeController {
                @GetMapping
                public String home() { return "home"; }
            }
            """;
        var result = parser.parse("repo", "src/HomeController.java", "sha1", controller);

        assertThat(result.events()).anySatisfy(event -> {
            assertThat(event).isInstanceOf(GraphEvent.EdgeEvent.class);
            var edge = (GraphEvent.EdgeEvent) event;
            assertThat(edge.type()).isEqualTo(GraphEdge.EdgeType.EXPOSES);
            assertThat(edge.to()).isEqualTo("GET /");
        });
    }

    @Test
    void parse_getMappingWithArrayPaths_usesFirstPath() {
        String source = """
            package com.acme;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class ItemController {
                @GetMapping({"/v1/items", "/v2/items"})
                public String list() { return "ok"; }
            }
            """;
        var result = parser.parse("repo", "src/ItemController.java", "sha", source);

        assertThat(result.events()).anySatisfy(event -> {
            assertThat(event).isInstanceOf(GraphEvent.NodeEvent.class);
            var node = (GraphEvent.NodeEvent) event;
            assertThat(node.id()).isEqualTo("GET /v1/items");
            assertThat(node.tags()).contains("ENDPOINT");
        });

        assertThat(result.events()).noneMatch(event ->
            event instanceof GraphEvent.NodeEvent node && node.id().contains("\","));
    }

    @Test
    void parse_constructorWithBeanParams_emitsDependsOnEdges() {
        String service = """
            package com.acme;
            import org.springframework.stereotype.Service;
            @Service
            public class OrderService {
                private final PaymentService paymentService;
                private final String name;
                public OrderService(PaymentService paymentService, String name) {
                    this.paymentService = paymentService;
                    this.name = name;
                }
            }
            """;
        var result = parser.parse("repo", "src/OrderService.java", "sha1", service);

        assertThat(result.events()).anySatisfy(event -> {
            assertThat(event).isInstanceOf(GraphEvent.EdgeEvent.class);
            var edge = (GraphEvent.EdgeEvent) event;
            assertThat(edge.type()).isEqualTo(GraphEdge.EdgeType.DEPENDS_ON);
            assertThat(edge.from()).isEqualTo("com.acme.OrderService");
            assertThat(edge.to()).isEqualTo("PaymentService");
        });

        assertThat(result.events()).noneMatch(event ->
            event instanceof GraphEvent.EdgeEvent edge
                && edge.type() == GraphEdge.EdgeType.DEPENDS_ON
                && edge.to().equals("String"));
    }

    @Test
    void parse_classChunkSymbolsContainAnnotations() {
        String source = """
            package com.acme;
            import org.springframework.stereotype.Service;
            @Service
            public class PaymentService {}
            """;
        var result = parser.parse("repo", "PaymentService.java", "sha", source);
        var classChunk = result.chunks().stream()
            .filter(chunk -> "CLASS".equals(chunk.type()))
            .findFirst()
            .orElseThrow();
        assertThat(classChunk.symbols()).contains("@Service");
    }

    @Test
    void parse_methodChunkSymbolsContainAnnotations() {
        String source = """
            package com.acme;
            import org.springframework.cache.annotation.Cacheable;
            public class OrderService {
                @Cacheable("orders")
                public String getOrder(String id) { return id; }
            }
            """;
        var result = parser.parse("repo", "OrderService.java", "sha", source);
        var methodChunk = result.chunks().stream()
            .filter(chunk -> "METHOD".equals(chunk.type()))
            .findFirst()
            .orElseThrow();
        assertThat(methodChunk.symbols()).contains("@Cacheable");
    }
}
