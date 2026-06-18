package com.acme.airetrieval.ingest.parser;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ApiSpecParser {
    private static final Map<String, java.util.function.Function<PathItem, Operation>> HTTP_METHODS = Map.of(
        "GET", PathItem::getGet,
        "POST", PathItem::getPost,
        "PUT", PathItem::getPut,
        "DELETE", PathItem::getDelete,
        "PATCH", PathItem::getPatch
    );

    public ParseResult parse(String repo, String path, String gitSha, String text) {
        List<Chunk> chunks = new ArrayList<>();
        List<GraphEvent> events = new ArrayList<>();

        ParseOptions options = new ParseOptions();
        options.setResolve(false);
        SwaggerParseResult parsed = new OpenAPIV3Parser().readContents(text, null, options);
        OpenAPI api = parsed.getOpenAPI();
        if (api == null || api.getPaths() == null) return new ParseResult(chunks, events);

        api.getPaths().forEach((apiPath, pathItem) ->
            HTTP_METHODS.forEach((method, getter) -> {
                Operation operation = getter.apply(pathItem);
                if (operation == null) return;
                String fqn = method + " " + apiPath;
                String id = path + "#" + fqn.replace(" ", "-").replace("/", "_");
                String summary = operation.getSummary() != null ? operation.getSummary() : fqn;
                String bodyText = operationText(fqn, summary, operation);

                chunks.add(new Chunk(id, repo, null, path, Domain.KNOWLEDGE, "OPENAPI_OP", fqn,
                    summary, apiPath, List.of(), gitSha, MarkdownParser.hash(bodyText), "openapi", bodyText, null));
                events.add(new GraphEvent.NodeEvent(fqn, "Endpoint", Set.of("ENDPOINT", "REPO:" + repo), fqn, summary));
                events.add(new GraphEvent.EdgeEvent(path, fqn, GraphEdge.EdgeType.SPECIFIES));
            })
        );
        return new ParseResult(chunks, events);
    }

    private static String operationText(String fqn, String summary, Operation operation) {
        StringBuilder sb = new StringBuilder();
        sb.append(fqn).append("\n").append(summary);
        if (operation.getDescription() != null) {
            sb.append("\n").append(operation.getDescription());
        }
        if (operation.getParameters() != null) {
            for (Parameter param : operation.getParameters()) {
                sb.append("\nparam:").append(param.getName());
                if (param.getDescription() != null) sb.append(" ").append(param.getDescription());
            }
        }
        if (operation.getRequestBody() != null && operation.getRequestBody().getDescription() != null) {
            sb.append("\nrequest:").append(operation.getRequestBody().getDescription());
        }
        if (operation.getResponses() != null) {
            operation.getResponses().forEach((code, response) -> {
                if (response.getDescription() != null) {
                    sb.append("\nresponse:").append(code).append(" ").append(response.getDescription());
                }
            });
        }
        return sb.toString().trim();
    }

    public record ParseResult(List<Chunk> chunks, List<GraphEvent> events) {}
}
