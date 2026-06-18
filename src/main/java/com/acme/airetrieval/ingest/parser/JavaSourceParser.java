package com.acme.airetrieval.ingest.parser;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;

import java.util.ArrayList;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class JavaSourceParser {
    private static final Set<String> BEANS = Set.of("Service", "Component", "Repository", "Controller", "RestController", "Configuration");
    private static final Set<String> MAPPINGS = Set.of("RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");
    private static final Map<String, String> HTTP_METHODS = Map.of(
        "GetMapping", "GET",
        "PostMapping", "POST",
        "PutMapping", "PUT",
        "DeleteMapping", "DELETE",
        "PatchMapping", "PATCH");
    private static final Set<String> NON_BEAN_TYPES = Set.of(
        "String", "Integer", "Long", "Double", "Float", "Boolean", "Object",
        "List", "Map", "Set", "Collection", "Optional", "Path", "File");

    public ParseResult parse(String repo, String path, String gitSha, String source) {
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(source);
        } catch (RuntimeException e) {
            return new ParseResult(List.of(), List.of());
        }
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        List<Chunk> chunks = new ArrayList<>();
        List<GraphEvent> events = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String typeFqn = pkg.isBlank() ? type.getNameAsString() : pkg + "." + type.getNameAsString();
            Set<String> tags = tags(type.getAnnotations());
            List<String> classSymbols = symbols(type.getAnnotations());
            String javadoc = type.getJavadoc().map(j -> j.toText()).orElse(null);
            events.add(new GraphEvent.NodeEvent(typeFqn, type.isInterface() ? "Interface" : "Class", tags, type.getNameAsString(), javadoc));
            chunks.add(new Chunk(typeFqn, repo, null, path, Domain.CODE, "CLASS", typeFqn, null, null, classSymbols,
                gitSha, MarkdownParser.hash(type.toString()), "java", typeFqn + "\n" + (javadoc == null ? "" : javadoc), null));
            for (ConstructorDeclaration constructor : type.getConstructors()) {
                for (Parameter parameter : constructor.getParameters()) {
                    String parameterType = parameter.getTypeAsString();
                    if (looksLikeBean(parameterType)) {
                        events.add(new GraphEvent.EdgeEvent(typeFqn, parameterType, GraphEdge.EdgeType.DEPENDS_ON));
                    }
                }
            }
            for (MethodDeclaration method : type.getMethods()) {
                String fqn = typeFqn + "#" + method.getSignature().asString();
                String signature = method.getDeclarationAsString(false, true, true);
                Set<String> methodTags = tags(method.getAnnotations());
                events.add(new GraphEvent.NodeEvent(fqn, "Method", methodTags, signature, method.getJavadoc().map(j -> j.toText()).orElse(null)));
                events.add(new GraphEvent.EdgeEvent(typeFqn, fqn, GraphEdge.EdgeType.DECLARES));
                for (AnnotationExpr annotation : method.getAnnotations()) {
                    if (MAPPINGS.contains(annotation.getNameAsString())) {
                        String endpoint = restEndpointKey(annotation);
                        events.add(new GraphEvent.NodeEvent(endpoint, "Endpoint", Set.of("ENDPOINT", "REPO:" + repo), endpoint, fqn));
                        events.add(new GraphEvent.EdgeEvent(fqn, endpoint, GraphEdge.EdgeType.EXPOSES));
                    }
                    if ("KafkaListener".equals(annotation.getNameAsString())) {
                        String topic = endpoint(annotation);
                        events.add(new GraphEvent.NodeEvent(topic, "Topic", Set.of("TOPIC"), topic, null));
                        events.add(new GraphEvent.EdgeEvent(fqn, topic, GraphEdge.EdgeType.CONSUMES));
                    }
                }
                method.findAll(MethodCallExpr.class).forEach(call -> {
                    String callee = call.getScope().map(scope -> scope + "." + call.getNameAsString()).orElse(call.getNameAsString());
                    events.add(new GraphEvent.EdgeEvent(fqn, callee, GraphEdge.EdgeType.CALLS));
                });
                String text = signature + "\n" + method.getBody().map(Object::toString).orElse("");
                chunks.add(new Chunk(fqn, repo, null, path, Domain.CODE, "METHOD", fqn, null, null, symbols(method.getAnnotations()),
                    gitSha, MarkdownParser.hash(text), "java", text, null));
            }
        }
        return new ParseResult(chunks, events);
    }

    private static List<String> symbols(List<AnnotationExpr> annotations) {
        return annotations.stream()
            .map(annotation -> "@" + annotation.getNameAsString())
            .collect(Collectors.toList());
    }

    private static Set<String> tags(List<AnnotationExpr> annotations) {
        Set<String> tags = new HashSet<>();
        for (AnnotationExpr annotation : annotations) {
            String name = annotation.getNameAsString();
            if (BEANS.contains(name)) tags.add("BEAN");
            if ("RestController".equals(name) || "Controller".equals(name)) tags.add("REST_CONTROLLER");
            if ("Repository".equals(name)) tags.add("REPOSITORY");
            if (MAPPINGS.contains(name)) tags.add("ENDPOINT");
            if ("KafkaListener".equals(name)) tags.add("KAFKA_CONSUMER");
        }
        return Set.copyOf(tags);
    }

    private static String endpoint(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            var val = single.getMemberValue();
            if (val instanceof ArrayInitializerExpr arr) {
                return arr.getValues().isEmpty() ? "/" : strip(arr.getValues().get(0).toString());
            }
            return strip(val.toString());
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if ("value".equals(pair.getNameAsString()) || "path".equals(pair.getNameAsString()) || "topics".equals(pair.getNameAsString())) {
                    var val = pair.getValue();
                    if (val instanceof ArrayInitializerExpr arr) {
                        return arr.getValues().isEmpty() ? "/" : strip(arr.getValues().get(0).toString());
                    }
                    return strip(val.toString());
                }
            }
        }
        return annotation.getNameAsString();
    }

    private static String strip(String value) {
        return value.replaceAll("^[\\{\\[\\s\"]+|[\\}\\]\\s\"]+$", "");
    }

    private static String restEndpointKey(AnnotationExpr annotation) {
        String annotationName = annotation.getNameAsString();
        String method = HTTP_METHODS.getOrDefault(annotationName, "ANY");
        return method + " " + restPath(annotation);
    }

    private static String restPath(AnnotationExpr annotation) {
        String raw = endpoint(annotation);
        if (MAPPINGS.contains(raw)) return "/";
        return raw.startsWith("/") ? raw : "/" + raw;
    }

    private static boolean looksLikeBean(String typeName) {
        if (typeName == null || typeName.isBlank()) return false;
        String base = typeName.contains("<") ? typeName.substring(0, typeName.indexOf('<')) : typeName;
        if (base.contains(".")) base = base.substring(base.lastIndexOf('.') + 1);
        return Character.isUpperCase(base.charAt(0)) && !NON_BEAN_TYPES.contains(base);
    }

    public record ParseResult(List<Chunk> chunks, List<GraphEvent> events) {}
}
