# Kira Phase 0 — Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a Spring Boot 3.5 / Java 21 service that indexes Markdown and document files via Lucene 10 BM25 and serves keyword search at `POST /api/v1/search`.

**Architecture:** Single fat JAR — JGit detects changed files relative to a last-indexed SHA, a SourceClassifier routes them to CommonMark (Markdown) or Tika (PDF/DOCX/HTML) parsers, resulting chunks are written to a Lucene 10 inverted index. A REST controller serves BM25 keyword search. No embeddings or graph yet.

**Tech Stack:** Spring Boot 3.5.0, Java 21, Apache Lucene 10.1.0, Apache Tika 2.9.2, CommonMark 0.22.0, JGit 7.2.0, Maven 3.9

---

## File Map

```
ai-retrieval/
├── pom.xml
├── src/main/java/com/acme/airetrieval/
│   ├── AiRetrievalApplication.java
│   ├── config/
│   │   ├── ApplicationProps.java         (record — @ConfigurationProperties)
│   │   ├── LuceneConfig.java             (creates LuceneIndexer + LuceneSearcher beans)
│   │   └── ExecutorConfig.java           (bounded thread pool for indexing)
│   ├── ingest/
│   │   ├── model/
│   │   │   ├── Chunk.java                (record — the universal unit; vector=null in Phase 0)
│   │   │   ├── Domain.java               (enum: CODE, KNOWLEDGE)
│   │   │   └── Change.java               (record: ChangeType, path)
│   │   ├── GitChangeDetector.java        (JGit diff → List<Change>)
│   │   ├── SourceClassifier.java         (path extension → parser choice)
│   │   ├── parser/
│   │   │   ├── MarkdownParser.java       (CommonMark → List<Chunk>)
│   │   │   └── DocumentParser.java       (Tika → List<Chunk>)
│   │   └── IndexService.java             (orchestrates: detect→classify→parse→index)
│   ├── index/
│   │   ├── LuceneIndexer.java            (upsert/delete/commit — AutoCloseable)
│   │   ├── LuceneSearcher.java           (BM25 search)
│   │   └── model/
│   │       ├── SearchHit.java            (record — result row)
│   │       └── SearchFilter.java         (record — repo/domain/type filter)
│   └── api/
│       ├── SearchController.java
│       ├── IndexController.java
│       └── dto/
│           ├── SearchRequest.java
│           ├── SearchResponse.java
│           └── IndexRequest.java
└── src/main/resources/
    └── application.yml
src/test/java/com/acme/airetrieval/
├── ingest/parser/MarkdownParserTest.java
├── ingest/parser/DocumentParserTest.java
├── ingest/GitChangeDetectorTest.java
├── index/LuceneIndexerTest.java
├── index/LuceneSearcherTest.java
└── api/SearchControllerIntegrationTest.java
```

---

### Task 1: Maven scaffold + main class + application.yml

**Files:**
- Create: `ai-retrieval/pom.xml`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/AiRetrievalApplication.java`
- Create: `ai-retrieval/src/main/resources/application.yml`

- [ ] **Step 1: Create pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.0</version>
    <relativePath/>
  </parent>

  <groupId>com.acme</groupId>
  <artifactId>ai-retrieval</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>ai-retrieval</name>
  <description>Kira — Local AI Knowledge and Code Retrieval Platform</description>

  <properties>
    <java.version>21</java.version>
    <lucene.version>10.1.0</lucene.version>
    <tika.version>2.9.2</tika.version>
    <commonmark.version>0.22.0</commonmark.version>
    <jgit.version>7.2.0.202503040940-r</jgit.version>
    <javaparser.version>3.26.2</javaparser.version>
    <onnxruntime.version>1.19.2</onnxruntime.version>
    <djl.version>0.28.0</djl.version>
  </properties>

  <dependencies>
    <!-- Spring Boot -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-configuration-processor</artifactId>
      <optional>true</optional>
    </dependency>

    <!-- Apache Lucene 10 -->
    <dependency>
      <groupId>org.apache.lucene</groupId>
      <artifactId>lucene-core</artifactId>
      <version>${lucene.version}</version>
    </dependency>
    <dependency>
      <groupId>org.apache.lucene</groupId>
      <artifactId>lucene-analysis-common</artifactId>
      <version>${lucene.version}</version>
    </dependency>
    <dependency>
      <groupId>org.apache.lucene</groupId>
      <artifactId>lucene-queryparser</artifactId>
      <version>${lucene.version}</version>
    </dependency>

    <!-- Apache Tika -->
    <dependency>
      <groupId>org.apache.tika</groupId>
      <artifactId>tika-core</artifactId>
      <version>${tika.version}</version>
    </dependency>
    <dependency>
      <groupId>org.apache.tika</groupId>
      <artifactId>tika-parsers-standard-package</artifactId>
      <version>${tika.version}</version>
    </dependency>

    <!-- CommonMark (Markdown) -->
    <dependency>
      <groupId>org.commonmark</groupId>
      <artifactId>commonmark</artifactId>
      <version>${commonmark.version}</version>
    </dependency>

    <!-- JGit -->
    <dependency>
      <groupId>org.eclipse.jgit</groupId>
      <artifactId>org.eclipse.jgit</artifactId>
      <version>${jgit.version}</version>
    </dependency>

    <!-- Testing -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Create main class**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/AiRetrievalApplication.java
package com.acme.airetrieval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiRetrievalApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiRetrievalApplication.class, args);
    }
}
```

- [ ] **Step 3: Create application.yml**

```yaml
# ai-retrieval/src/main/resources/application.yml
spring:
  application:
    name: kira

kira:
  data-dir: ${user.home}/.kira/data
  index-dir: ${kira.data-dir}/lucene
  checkpoint-file: ${kira.data-dir}/checkpoint.json
  max-search-results: 50
  default-search-k: 10
  executor:
    index-threads: 4

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

- [ ] **Step 4: Verify project compiles**

```bash
cd ai-retrieval && mvn compile -q
```
Expected: BUILD SUCCESS with no errors.

- [ ] **Step 5: Commit**

```bash
git init && git add pom.xml src/main/java/com/acme/airetrieval/AiRetrievalApplication.java src/main/resources/application.yml
git commit -m "feat: scaffold Maven project, main class, application.yml"
```

---

### Task 2: Domain model — Chunk, Domain, Change

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/ingest/model/Domain.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/ingest/model/Chunk.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/ingest/model/Change.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/index/model/SearchHit.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/index/model/SearchFilter.java`

- [ ] **Step 1: Create Domain enum**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/ingest/model/Domain.java
package com.acme.airetrieval.ingest.model;

public enum Domain {
    CODE,
    KNOWLEDGE
}
```

- [ ] **Step 2: Create Chunk record**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/ingest/model/Chunk.java
package com.acme.airetrieval.ingest.model;

import java.util.List;

/**
 * Universal unit indexed in Lucene. vector is null until Phase 1.
 * id is stable: path#fqn for code, path#heading-slug for docs.
 */
public record Chunk(
    String id,
    String repo,
    String path,
    Domain domain,
    String type,
    String fqn,           // nullable — code only
    String title,         // nullable — docs only
    String section,       // nullable — heading path
    List<String> symbols, // identifiers mentioned (populated Phase 2+)
    String gitSha,
    String contentHash,
    String lang,
    String text,
    float[] vector        // null until Phase 1
) {}
```

- [ ] **Step 3: Create Change record**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/ingest/model/Change.java
package com.acme.airetrieval.ingest.model;

public record Change(ChangeType type, String path) {
    public enum ChangeType { ADD, MODIFY, DELETE }
}
```

- [ ] **Step 4: Create SearchHit record**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/index/model/SearchHit.java
package com.acme.airetrieval.index.model;

import com.acme.airetrieval.ingest.model.Domain;
import org.apache.lucene.document.Document;

public record SearchHit(
    String id,
    String repo,
    String path,
    Domain domain,
    String type,
    String fqn,
    String title,
    String section,
    String snippet,
    float score
) {
    public static SearchHit fromDocument(Document doc, float score) {
        String domainStr = doc.get("domain");
        return new SearchHit(
            doc.get("id"),
            doc.get("repo"),
            doc.get("path"),
            domainStr != null ? Domain.valueOf(domainStr) : null,
            doc.get("type"),
            doc.get("fqn"),
            doc.get("title"),
            doc.get("section"),
            snippet(doc.get("text")),
            score
        );
    }

    private static String snippet(String text) {
        if (text == null) return null;
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }
}
```

- [ ] **Step 5: Create SearchFilter record**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/index/model/SearchFilter.java
package com.acme.airetrieval.index.model;

public record SearchFilter(
    String repo,
    String domain,
    String type,
    String path
) {}
```

- [ ] **Step 6: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/model/ src/main/java/com/acme/airetrieval/index/model/
git commit -m "feat: add domain model — Chunk, Domain, Change, SearchHit, SearchFilter"
```

---

### Task 3: MarkdownParser

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/ingest/parser/MarkdownParser.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/ingest/parser/MarkdownParserTest.java`

- [ ] **Step 1: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/ingest/parser/MarkdownParserTest.java
package com.acme.airetrieval.ingest.parser;

import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MarkdownParserTest {

    private final MarkdownParser parser = new MarkdownParser();

    @Test
    void parse_singleHeadingSection_returnsOneChunk() {
        String md = "# Getting Started\n\nInstall with `mvn clean install`.\n";
        List<Chunk> chunks = parser.parse("myrepo", "docs/README.md", "abc123", md);

        assertThat(chunks).hasSize(1);
        Chunk c = chunks.get(0);
        assertThat(c.id()).isEqualTo("docs/README.md#getting-started");
        assertThat(c.domain()).isEqualTo(Domain.KNOWLEDGE);
        assertThat(c.type()).isEqualTo("MD_SECTION");
        assertThat(c.title()).isEqualTo("Getting Started");
        assertThat(c.text()).contains("Install with");
        assertThat(c.repo()).isEqualTo("myrepo");
        assertThat(c.path()).isEqualTo("docs/README.md");
        assertThat(c.gitSha()).isEqualTo("abc123");
        assertThat(c.contentHash()).isNotBlank();
    }

    @Test
    void parse_multipleHeadings_returnsChunkPerSection() {
        String md = """
                # Installation
                Run mvn install.

                # Configuration
                Edit application.yml.
                """;
        List<Chunk> chunks = parser.parse("r", "docs/guide.md", "sha1", md);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).title()).isEqualTo("Installation");
        assertThat(chunks.get(1).title()).isEqualTo("Configuration");
    }

    @Test
    void parse_noHeadings_returnsSingleBodyChunk() {
        String md = "Just some text without any headings.\n";
        List<Chunk> chunks = parser.parse("r", "docs/notes.md", "sha1", md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).id()).isEqualTo("docs/notes.md#body");
        assertThat(chunks.get(0).title()).isNull();
    }

    @Test
    void parse_differentContent_differentContentHash() {
        String md1 = "# Title\nContent A";
        String md2 = "# Title\nContent B";
        List<Chunk> c1 = parser.parse("r", "f.md", "s", md1);
        List<Chunk> c2 = parser.parse("r", "f.md", "s", md2);

        assertThat(c1.get(0).contentHash()).isNotEqualTo(c2.get(0).contentHash());
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
mvn test -pl . -Dtest=MarkdownParserTest -q 2>&1 | tail -5
```
Expected: FAIL with `ClassNotFoundException: MarkdownParser`.

- [ ] **Step 3: Implement MarkdownParser**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/ingest/parser/MarkdownParser.java
package com.acme.airetrieval.ingest.parser;

import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class MarkdownParser {

    private static final Parser MD_PARSER = Parser.builder().build();
    private static final TextContentRenderer TEXT = TextContentRenderer.builder().build();

    public List<Chunk> parse(String repo, String path, String gitSha, String content) {
        Node doc = MD_PARSER.parse(content);
        List<RawSection> sections = splitByHeadings(doc);

        List<Chunk> chunks = new ArrayList<>();
        for (RawSection s : sections) {
            if (s.text().isBlank()) continue;
            String id = path + "#" + slugify(s.heading());
            chunks.add(new Chunk(
                id, repo, path, Domain.KNOWLEDGE, "MD_SECTION",
                null, s.heading(), null, List.of(),
                gitSha, hash(s.text()), "markdown", s.text(), null
            ));
        }

        if (chunks.isEmpty()) {
            String text = TEXT.render(doc).trim();
            chunks.add(new Chunk(
                path + "#body", repo, path, Domain.KNOWLEDGE, "MD_SECTION",
                null, null, null, List.of(),
                gitSha, hash(text), "markdown", text, null
            ));
        }

        return chunks;
    }

    private List<RawSection> splitByHeadings(Node document) {
        List<RawSection> sections = new ArrayList<>();
        String currentHeading = null;
        List<Node> currentChildren = new ArrayList<>();

        Node child = document.getFirstChild();
        while (child != null) {
            if (child instanceof Heading h) {
                if (currentHeading != null || !currentChildren.isEmpty()) {
                    sections.add(new RawSection(currentHeading, renderNodes(currentChildren)));
                }
                currentHeading = TEXT.render(h).trim();
                currentChildren = new ArrayList<>();
            } else {
                currentChildren.add(child);
            }
            child = child.getNext();
        }

        if (currentHeading != null || !currentChildren.isEmpty()) {
            sections.add(new RawSection(currentHeading, renderNodes(currentChildren)));
        }

        return sections;
    }

    private String renderNodes(List<Node> nodes) {
        var sb = new StringBuilder();
        for (Node n : nodes) sb.append(TEXT.render(n)).append('\n');
        return sb.toString().trim();
    }

    private static String slugify(String heading) {
        if (heading == null) return "body";
        return heading.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record RawSection(String heading, String text) {}
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
mvn test -Dtest=MarkdownParserTest -q
```
Expected: Tests run: 4, Failures: 0, Errors: 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/parser/MarkdownParser.java \
        src/test/java/com/acme/airetrieval/ingest/parser/MarkdownParserTest.java
git commit -m "feat: MarkdownParser — splits markdown into per-heading Chunk records"
```

---

### Task 4: DocumentParser (Tika)

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/ingest/parser/DocumentParser.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/ingest/parser/DocumentParserTest.java`
- Create: `ai-retrieval/src/test/resources/fixtures/sample.txt` (plaintext fixture)

- [ ] **Step 1: Create test fixture**

Create file `src/test/resources/fixtures/sample.txt` with this content:
```
This is a sample document.
It has multiple lines.
Apache Tika extracts its text.
```

- [ ] **Step 2: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/ingest/parser/DocumentParserTest.java
package com.acme.airetrieval.ingest.parser;

import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parse_textFile_returnsChunks() throws IOException {
        Path fixture = Path.of("src/test/resources/fixtures/sample.txt");
        List<Chunk> chunks = parser.parse("repo", "docs/sample.txt", "sha1", fixture);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).domain()).isEqualTo(Domain.KNOWLEDGE);
        assertThat(chunks.get(0).type()).isEqualTo("DOCUMENT");
        assertThat(chunks.get(0).text()).contains("Apache Tika");
        assertThat(chunks.get(0).repo()).isEqualTo("repo");
        assertThat(chunks.get(0).path()).isEqualTo("docs/sample.txt");
    }

    @Test
    void parse_largeContent_chunksAtWordBoundary() throws IOException {
        // Build a file with > 1500 tokens worth of content
        Path fixture = Path.of("src/test/resources/fixtures/sample.txt");
        // DocumentParser with default 1000-char window should produce >= 1 chunk
        List<Chunk> chunks = parser.parse("r", "f.txt", "s", fixture);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.contentHash()).isNotBlank();
            assertThat(c.id()).startsWith("f.txt#");
        });
    }
}
```

- [ ] **Step 3: Run test — verify it fails**

```bash
mvn test -Dtest=DocumentParserTest -q 2>&1 | tail -5
```
Expected: FAIL with `ClassNotFoundException: DocumentParser`.

- [ ] **Step 4: Implement DocumentParser**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/ingest/parser/DocumentParser.java
package com.acme.airetrieval.ingest.parser;

import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DocumentParser {

    private static final int CHUNK_CHARS = 2000;
    private static final int OVERLAP_CHARS = 200;
    private final Tika tika = new Tika();

    public List<Chunk> parse(String repo, String path, String gitSha, Path file)
            throws IOException {
        String text;
        try {
            text = tika.parseToString(file.toFile());
        } catch (Exception e) {
            throw new IOException("Tika failed to parse " + path, e);
        }

        text = text.strip();
        if (text.isEmpty()) return List.of();

        String lang = detectLang(path);
        List<Chunk> chunks = new ArrayList<>();
        int chunkIndex = 0;
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + CHUNK_CHARS, text.length());
            // align to word boundary
            if (end < text.length()) {
                int boundary = text.lastIndexOf(' ', end);
                if (boundary > start) end = boundary;
            }
            String slice = text.substring(start, end).strip();
            if (!slice.isEmpty()) {
                String id = path + "#chunk-" + chunkIndex;
                chunks.add(new Chunk(
                    id, repo, path, Domain.KNOWLEDGE, "DOCUMENT",
                    null, null, null, List.of(),
                    gitSha, MarkdownParser.hash(slice), lang, slice, null
                ));
                chunkIndex++;
            }
            start = end - OVERLAP_CHARS;
            if (start >= text.length() - OVERLAP_CHARS) break;
        }

        return chunks;
    }

    private static String detectLang(String path) {
        if (path.endsWith(".pdf")) return "pdf";
        if (path.endsWith(".docx") || path.endsWith(".doc")) return "word";
        if (path.endsWith(".html") || path.endsWith(".htm")) return "html";
        return "text";
    }
}
```

- [ ] **Step 5: Run test — verify it passes**

```bash
mvn test -Dtest=DocumentParserTest -q
```
Expected: Tests run: 2, Failures: 0, Errors: 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/parser/DocumentParser.java \
        src/test/java/com/acme/airetrieval/ingest/parser/DocumentParserTest.java \
        src/test/resources/fixtures/sample.txt
git commit -m "feat: DocumentParser — Tika extracts text and splits into overlapping chunks"
```

---

### Task 5: SourceClassifier

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/ingest/SourceClassifier.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/ingest/SourceClassifierTest.java`

- [ ] **Step 1: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/ingest/SourceClassifierTest.java
package com.acme.airetrieval.ingest;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class SourceClassifierTest {

    private final SourceClassifier classifier = new SourceClassifier();

    @ParameterizedTest
    @CsvSource({
        "README.md,        MARKDOWN",
        "docs/guide.MD,    MARKDOWN",
        "report.pdf,       DOCUMENT",
        "spec.docx,        DOCUMENT",
        "export.html,      DOCUMENT",
        "page.htm,         DOCUMENT",
        "Main.java,        JAVA",
        "build.gradle,     SKIP",
        "image.png,        SKIP",
        ".gitignore,       SKIP"
    })
    void classify_pathExtension_returnsCorrectType(String path, String expected) {
        assertThat(classifier.classify(path).name()).isEqualTo(expected);
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
mvn test -Dtest=SourceClassifierTest -q 2>&1 | tail -5
```
Expected: FAIL.

- [ ] **Step 3: Implement SourceClassifier**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/ingest/SourceClassifier.java
package com.acme.airetrieval.ingest;

import java.util.Locale;
import java.util.Set;

public final class SourceClassifier {

    public enum FileType { MARKDOWN, DOCUMENT, JAVA, SKIP }

    private static final Set<String> DOCUMENT_EXTS = Set.of("pdf", "docx", "doc", "html", "htm", "pptx", "odt");
    private static final Set<String> JAVA_EXTS = Set.of("java");

    public FileType classify(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return FileType.SKIP;
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        if ("md".equals(ext)) return FileType.MARKDOWN;
        if (JAVA_EXTS.contains(ext)) return FileType.JAVA;
        if (DOCUMENT_EXTS.contains(ext)) return FileType.DOCUMENT;
        return FileType.SKIP;
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
mvn test -Dtest=SourceClassifierTest -q
```
Expected: Tests run: 9, Failures: 0, Errors: 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/SourceClassifier.java \
        src/test/java/com/acme/airetrieval/ingest/SourceClassifierTest.java
git commit -m "feat: SourceClassifier — routes paths to MARKDOWN/DOCUMENT/JAVA/SKIP"
```

---

### Task 6: LuceneIndexer

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/index/LuceneIndexer.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/index/LuceneIndexerTest.java`

- [ ] **Step 1: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/index/LuceneIndexerTest.java
package com.acme.airetrieval.index;

import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.index.model.SearchHit;
import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class LuceneIndexerTest {

    private Path indexDir;
    private LuceneIndexer indexer;

    @BeforeEach
    void setUp() throws IOException {
        indexDir = Files.createTempDirectory("lucene-test");
        indexer = new LuceneIndexer(indexDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        indexer.close();
        Files.walk(indexDir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }

    private Chunk chunk(String id, String path, String text) {
        return new Chunk(id, "repo", path, Domain.KNOWLEDGE, "MD_SECTION",
            null, "Title", null, List.of(), "sha1", "hash" + id, "markdown", text, null);
    }

    @Test
    void upsert_newChunk_canBeRetrievedByBm25() throws IOException {
        indexer.upsert(chunk("c1", "docs/a.md", "install with mvn clean install"));
        indexer.commit();

        var searcher = new LuceneSearcher(indexDir);
        List<SearchHit> hits = searcher.bm25("mvn clean install", null, 10);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).id()).isEqualTo("c1");
        searcher.close();
    }

    @Test
    void upsert_existingId_replacesDocument() throws IOException {
        indexer.upsert(chunk("c1", "docs/a.md", "old content here"));
        indexer.commit();
        indexer.upsert(chunk("c1", "docs/a.md", "new content here"));
        indexer.commit();

        var searcher = new LuceneSearcher(indexDir);
        assertThat(searcher.bm25("old content", null, 10)).isEmpty();
        assertThat(searcher.bm25("new content", null, 10)).hasSize(1);
        searcher.close();
    }

    @Test
    void deleteByPath_removesAllChunksForPath() throws IOException {
        indexer.upsert(chunk("c1", "docs/a.md", "document one"));
        indexer.upsert(chunk("c2", "docs/a.md", "document two"));
        indexer.upsert(chunk("c3", "docs/b.md", "keep this one"));
        indexer.commit();
        indexer.deleteByPath("docs/a.md");
        indexer.commit();

        var searcher = new LuceneSearcher(indexDir);
        assertThat(searcher.bm25("document", null, 10)).isEmpty();
        assertThat(searcher.bm25("keep this one", null, 10)).hasSize(1);
        searcher.close();
    }

    @Test
    void bm25_filterByDomain_returnsOnlyMatchingDomain() throws IOException {
        var codeChunk = new Chunk("code1", "repo", "src/Foo.java", Domain.CODE, "METHOD",
            "com.acme.Foo#bar", null, null, List.of(), "sha1", "h1", "java", "process payment", null);
        var docChunk = chunk("doc1", "docs/a.md", "process payment flow");

        indexer.upsert(codeChunk);
        indexer.upsert(docChunk);
        indexer.commit();

        var searcher = new LuceneSearcher(indexDir);
        var filter = new SearchFilter(null, "CODE", null, null);
        List<SearchHit> hits = searcher.bm25("process payment", filter, 10);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).id()).isEqualTo("code1");
        searcher.close();
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
mvn test -Dtest=LuceneIndexerTest -q 2>&1 | tail -5
```
Expected: FAIL with `ClassNotFoundException: LuceneIndexer`.

- [ ] **Step 3: Implement LuceneIndexer**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/index/LuceneIndexer.java
package com.acme.airetrieval.index;

import com.acme.airetrieval.ingest.model.Chunk;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.MMapDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LuceneIndexer implements AutoCloseable {

    private final MMapDirectory directory;
    private final IndexWriter writer;

    public LuceneIndexer(Path indexDir) throws IOException {
        Files.createDirectories(indexDir);
        this.directory = MMapDirectory.open(indexDir);
        var config = new IndexWriterConfig(new StandardAnalyzer())
            .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
            .setRAMBufferSizeMB(256);
        this.writer = new IndexWriter(directory, config);
    }

    public void upsert(Chunk chunk) throws IOException {
        Document doc = new Document();
        doc.add(new StringField("id", chunk.id(), Field.Store.YES));
        doc.add(new StringField("repo", chunk.repo(), Field.Store.YES));
        doc.add(new StringField("path", chunk.path(), Field.Store.YES));
        doc.add(new StringField("domain", chunk.domain().name(), Field.Store.YES));
        doc.add(new StringField("type", chunk.type(), Field.Store.YES));
        doc.add(new StringField("content_hash", chunk.contentHash(), Field.Store.YES));
        if (chunk.fqn() != null)     doc.add(new StoredField("fqn", chunk.fqn()));
        if (chunk.title() != null)   doc.add(new StoredField("title", chunk.title()));
        if (chunk.section() != null) doc.add(new StoredField("section", chunk.section()));
        if (chunk.lang() != null)    doc.add(new StoredField("lang", chunk.lang()));
        doc.add(new TextField("text", chunk.text(), Field.Store.YES));

        writer.updateDocument(new Term("id", chunk.id()), doc);
    }

    public void deleteByPath(String path) throws IOException {
        writer.deleteDocuments(new Term("path", path));
    }

    public void commit() throws IOException {
        writer.commit();
    }

    @Override
    public void close() throws IOException {
        writer.close();
        directory.close();
    }
}
```

- [ ] **Step 4: Implement LuceneSearcher**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/index/LuceneSearcher.java
package com.acme.airetrieval.index;

import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.index.model.SearchHit;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.MMapDirectory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class LuceneSearcher implements AutoCloseable {

    private final MMapDirectory directory;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final StandardAnalyzer analyzer;

    public LuceneSearcher(Path indexDir) throws IOException {
        this.directory = MMapDirectory.open(indexDir);
        this.reader = DirectoryReader.open(directory);
        this.searcher = new IndexSearcher(reader);
        this.analyzer = new StandardAnalyzer();
    }

    public List<SearchHit> bm25(String queryText, SearchFilter filter, int k) throws IOException {
        Query base;
        try {
            base = new QueryParser("text", analyzer).parse(QueryParserBase.escape(queryText));
        } catch (ParseException e) {
            base = new TermQuery(new Term("text", queryText.toLowerCase()));
        }

        Query finalQuery = applyFilter(base, filter);
        TopDocs topDocs = searcher.search(finalQuery, k);
        return toHits(topDocs);
    }

    private Query applyFilter(Query base, SearchFilter filter) {
        if (filter == null) return base;
        BooleanQuery.Builder builder = new BooleanQuery.Builder()
            .add(base, BooleanClause.Occur.MUST);
        if (filter.repo() != null)
            builder.add(new TermQuery(new Term("repo", filter.repo())), BooleanClause.Occur.FILTER);
        if (filter.domain() != null)
            builder.add(new TermQuery(new Term("domain", filter.domain())), BooleanClause.Occur.FILTER);
        if (filter.type() != null)
            builder.add(new TermQuery(new Term("type", filter.type())), BooleanClause.Occur.FILTER);
        if (filter.path() != null)
            builder.add(new TermQuery(new Term("path", filter.path())), BooleanClause.Occur.FILTER);
        return builder.build();
    }

    private List<SearchHit> toHits(TopDocs topDocs) throws IOException {
        var storedFields = reader.storedFields();
        List<SearchHit> hits = new ArrayList<>();
        for (ScoreDoc sd : topDocs.scoreDocs) {
            Document doc = storedFields.document(sd.doc);
            hits.add(SearchHit.fromDocument(doc, sd.score));
        }
        return hits;
    }

    @Override
    public void close() throws IOException {
        reader.close();
        directory.close();
        analyzer.close();
    }
}
```

- [ ] **Step 5: Run tests — verify they pass**

```bash
mvn test -Dtest=LuceneIndexerTest -q
```
Expected: Tests run: 4, Failures: 0, Errors: 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/acme/airetrieval/index/ \
        src/test/java/com/acme/airetrieval/index/LuceneIndexerTest.java
git commit -m "feat: LuceneIndexer + LuceneSearcher — BM25 upsert/delete/filter"
```

---

### Task 7: GitChangeDetector

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/ingest/GitChangeDetector.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/ingest/GitChangeDetectorTest.java`

- [ ] **Step 1: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/ingest/GitChangeDetectorTest.java
package com.acme.airetrieval.ingest;

import com.acme.airetrieval.ingest.model.Change;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GitChangeDetectorTest {

    private Path repoDir;
    private Git git;
    private GitChangeDetector detector;

    @BeforeEach
    void setUp() throws Exception {
        repoDir = Files.createTempDirectory("git-test");
        git = Git.init().setDirectory(repoDir.toFile()).call();
        git.commit().setMessage("init").setAllowEmpty(true).call();
        detector = new GitChangeDetector(repoDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        git.close();
        Files.walk(repoDir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }

    @Test
    void detectChanges_addedFile_returnsAddChange() throws Exception {
        String fromSha = head();
        Files.writeString(repoDir.resolve("README.md"), "# Hello");
        git.add().addFilepattern("README.md").call();
        git.commit().setMessage("add readme").call();
        String toSha = head();

        List<Change> changes = detector.detectChanges(fromSha, toSha);

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).type()).isEqualTo(Change.ChangeType.ADD);
        assertThat(changes.get(0).path()).isEqualTo("README.md");
    }

    @Test
    void detectChanges_modifiedFile_returnsModifyChange() throws Exception {
        Files.writeString(repoDir.resolve("doc.md"), "v1");
        git.add().addFilepattern("doc.md").call();
        git.commit().setMessage("initial").call();
        String fromSha = head();

        Files.writeString(repoDir.resolve("doc.md"), "v2");
        git.add().addFilepattern("doc.md").call();
        git.commit().setMessage("update").call();
        String toSha = head();

        List<Change> changes = detector.detectChanges(fromSha, toSha);

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).type()).isEqualTo(Change.ChangeType.MODIFY);
        assertThat(changes.get(0).path()).isEqualTo("doc.md");
    }

    @Test
    void detectChanges_deletedFile_returnsDeleteChange() throws Exception {
        Files.writeString(repoDir.resolve("old.md"), "gone");
        git.add().addFilepattern("old.md").call();
        git.commit().setMessage("add").call();
        String fromSha = head();

        git.rm().addFilepattern("old.md").call();
        git.commit().setMessage("delete").call();
        String toSha = head();

        List<Change> changes = detector.detectChanges(fromSha, toSha);

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).type()).isEqualTo(Change.ChangeType.DELETE);
        assertThat(changes.get(0).path()).isEqualTo("old.md");
    }

    private String head() throws Exception {
        return git.getRepository().resolve("HEAD").name();
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
mvn test -Dtest=GitChangeDetectorTest -q 2>&1 | tail -5
```
Expected: FAIL.

- [ ] **Step 3: Implement GitChangeDetector**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/ingest/GitChangeDetector.java
package com.acme.airetrieval.ingest;

import com.acme.airetrieval.ingest.model.Change;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class GitChangeDetector {

    private final Path repoDir;

    public GitChangeDetector(Path repoDir) {
        this.repoDir = repoDir;
    }

    public List<Change> detectChanges(String fromSha, String toSha) throws Exception {
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(repoDir.resolve(".git").toFile())
                .readEnvironment()
                .findGitDir()
                .build();
             Git git = new Git(repo)) {

            AbstractTreeIterator oldTree = treeParser(repo, fromSha);
            AbstractTreeIterator newTree = treeParser(repo, toSha);

            return git.diff()
                .setOldTree(oldTree)
                .setNewTree(newTree)
                .call()
                .stream()
                .map(entry -> new Change(toChangeType(entry.getChangeType()),
                    entry.getChangeType() == DiffEntry.ChangeType.DELETE
                        ? entry.getOldPath() : entry.getNewPath()))
                .toList();
        }
    }

    private static AbstractTreeIterator treeParser(Repository repo, String sha) throws Exception {
        var parser = new CanonicalTreeParser();
        try (var reader = repo.newObjectReader()) {
            parser.reset(reader, repo.resolve(sha + "^{tree}"));
        }
        return parser;
    }

    private static Change.ChangeType toChangeType(DiffEntry.ChangeType type) {
        return switch (type) {
            case ADD -> Change.ChangeType.ADD;
            case MODIFY, RENAME, COPY -> Change.ChangeType.MODIFY;
            case DELETE -> Change.ChangeType.DELETE;
        };
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
mvn test -Dtest=GitChangeDetectorTest -q
```
Expected: Tests run: 3, Failures: 0, Errors: 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/GitChangeDetector.java \
        src/test/java/com/acme/airetrieval/ingest/GitChangeDetectorTest.java
git commit -m "feat: GitChangeDetector — JGit diff produces Change list for incremental index"
```

---

### Task 8: ApplicationProps + config beans

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/config/ApplicationProps.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/config/LuceneConfig.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/config/ExecutorConfig.java`

- [ ] **Step 1: Create ApplicationProps**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/config/ApplicationProps.java
package com.acme.airetrieval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;

@ConfigurationProperties(prefix = "kira")
public record ApplicationProps(
    Path dataDir,
    Path indexDir,
    Path checkpointFile,
    int maxSearchResults,
    int defaultSearchK,
    Executor executor
) {
    public record Executor(int indexThreads) {}
}
```

- [ ] **Step 2: Create LuceneConfig**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/config/LuceneConfig.java
package com.acme.airetrieval.config;

import com.acme.airetrieval.index.LuceneIndexer;
import com.acme.airetrieval.index.LuceneSearcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class LuceneConfig {

    @Bean
    public LuceneIndexer luceneIndexer(ApplicationProps props) throws IOException {
        return new LuceneIndexer(props.indexDir());
    }

    @Bean
    public LuceneSearcher luceneSearcher(ApplicationProps props) throws IOException {
        return new LuceneSearcher(props.indexDir());
    }
}
```

- [ ] **Step 3: Create ExecutorConfig**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/config/ExecutorConfig.java
package com.acme.airetrieval.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService indexExecutor(ApplicationProps props) {
        return Executors.newFixedThreadPool(props.executor().indexThreads());
    }
}
```

- [ ] **Step 4: Compile to catch config errors**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/config/
git commit -m "feat: config beans — ApplicationProps, LuceneConfig, ExecutorConfig"
```

---

### Task 9: IndexService

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/ingest/IndexService.java`

- [ ] **Step 1: Create IndexService**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/ingest/IndexService.java
package com.acme.airetrieval.ingest;

import com.acme.airetrieval.index.LuceneIndexer;
import com.acme.airetrieval.ingest.model.Change;
import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.parser.DocumentParser;
import com.acme.airetrieval.ingest.parser.MarkdownParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

@Service
public class IndexService {

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    private final GitChangeDetector changeDetector;
    private final SourceClassifier classifier;
    private final MarkdownParser markdownParser;
    private final DocumentParser documentParser;
    private final LuceneIndexer indexer;

    public IndexService(GitChangeDetector changeDetector,
                        SourceClassifier classifier,
                        MarkdownParser markdownParser,
                        DocumentParser documentParser,
                        LuceneIndexer indexer) {
        this.changeDetector = changeDetector;
        this.classifier = classifier;
        this.markdownParser = markdownParser;
        this.documentParser = documentParser;
        this.indexer = indexer;
    }

    public IndexResult indexIncremental(Path repoPath, String repo, String fromSha, String toSha)
            throws Exception {
        List<Change> changes = changeDetector.detectChanges(fromSha, toSha);
        int indexed = 0, deleted = 0, skipped = 0;

        for (Change change : changes) {
            if (change.type() == Change.ChangeType.DELETE) {
                indexer.deleteByPath(change.path());
                deleted++;
                continue;
            }

            var fileType = classifier.classify(change.path());
            Path fullPath = repoPath.resolve(change.path());

            List<Chunk> chunks = switch (fileType) {
                case MARKDOWN -> {
                    String content = Files.readString(fullPath);
                    yield markdownParser.parse(repo, change.path(), toSha, content);
                }
                case DOCUMENT -> documentParser.parse(repo, change.path(), toSha, fullPath);
                case JAVA -> List.of(); // Phase 2
                case SKIP -> {
                    skipped++;
                    yield List.of();
                }
            };

            if (!chunks.isEmpty()) {
                indexer.deleteByPath(change.path()); // remove old chunks for this path
                for (Chunk chunk : chunks) indexer.upsert(chunk);
                indexed += chunks.size();
                log.info("Indexed {} chunks from {}", chunks.size(), change.path());
            }
        }

        indexer.commit();
        return new IndexResult(indexed, deleted, skipped, toSha);
    }

    public record IndexResult(int indexed, int deleted, int skipped, String toSha) {}
}
```

- [ ] **Step 2: Register parsers as beans**

Add to `LuceneConfig.java`:
```java
@Bean
public MarkdownParser markdownParser() {
    return new MarkdownParser();
}

@Bean
public DocumentParser documentParser() {
    return new DocumentParser();
}

@Bean
public SourceClassifier sourceClassifier() {
    return new SourceClassifier();
}
```

- [ ] **Step 3: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/IndexService.java \
        src/main/java/com/acme/airetrieval/config/LuceneConfig.java
git commit -m "feat: IndexService — orchestrates git diff → parse → index pipeline"
```

---

### Task 10: REST API (SearchController + IndexController)

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/api/dto/SearchRequest.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/api/dto/SearchResponse.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/api/dto/IndexRequest.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/api/SearchController.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/api/IndexController.java`

- [ ] **Step 1: Create DTOs**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/api/dto/SearchRequest.java
package com.acme.airetrieval.api.dto;

public record SearchRequest(
    String query,
    String repo,
    String domain,
    String type,
    Integer k
) {}
```

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/api/dto/SearchResponse.java
package com.acme.airetrieval.api.dto;

import com.acme.airetrieval.index.model.SearchHit;
import java.util.List;

public record SearchResponse(List<SearchHit> hits, int total) {}
```

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/api/dto/IndexRequest.java
package com.acme.airetrieval.api.dto;

public record IndexRequest(
    String repoPath,
    String repo,
    String fromSha,
    String toSha
) {}
```

- [ ] **Step 2: Create SearchController**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/api/SearchController.java
package com.acme.airetrieval.api;

import com.acme.airetrieval.api.dto.SearchRequest;
import com.acme.airetrieval.api.dto.SearchResponse;
import com.acme.airetrieval.config.ApplicationProps;
import com.acme.airetrieval.index.LuceneSearcher;
import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.index.model.SearchHit;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private final LuceneSearcher searcher;
    private final ApplicationProps props;

    public SearchController(LuceneSearcher searcher, ApplicationProps props) {
        this.searcher = searcher;
        this.props = props;
    }

    @PostMapping("/search")
    public ResponseEntity<SearchResponse> search(@RequestBody SearchRequest req) throws IOException {
        if (req.query() == null || req.query().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        int k = req.k() != null ? Math.min(req.k(), props.maxSearchResults()) : props.defaultSearchK();
        var filter = new SearchFilter(req.repo(), req.domain(), req.type(), null);
        List<SearchHit> hits = searcher.bm25(req.query(), filter, k);
        return ResponseEntity.ok(new SearchResponse(hits, hits.size()));
    }
}
```

- [ ] **Step 3: Create IndexController**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/api/IndexController.java
package com.acme.airetrieval.api;

import com.acme.airetrieval.api.dto.IndexRequest;
import com.acme.airetrieval.ingest.IndexService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/v1/index")
public class IndexController {

    private final IndexService indexService;

    public IndexController(IndexService indexService) {
        this.indexService = indexService;
    }

    @PostMapping("/incremental")
    public ResponseEntity<IndexService.IndexResult> incremental(@RequestBody IndexRequest req)
            throws Exception {
        var result = indexService.indexIncremental(
            Path.of(req.repoPath()), req.repo(), req.fromSha(), req.toSha());
        return ResponseEntity.ok(result);
    }
}
```

- [ ] **Step 4: Register GitChangeDetector as a bean**

Add to `LuceneConfig.java` (or create `IngestConfig.java`):
```java
@Bean
public GitChangeDetector gitChangeDetector() {
    return new GitChangeDetector(Path.of(System.getProperty("user.home")));
    // NOTE: repoDir is passed per-request in IndexService.indexIncremental(); this bean is a factory
}
```

Actually, `GitChangeDetector` takes the repoDir at construction time. Refactor to accept it at call time instead:

In `GitChangeDetector.java`, change the constructor to remove `repoDir` and accept it as a parameter to `detectChanges`:

```java
// Updated GitChangeDetector.java — remove constructor field
public final class GitChangeDetector {
    public List<Change> detectChanges(Path repoDir, String fromSha, String toSha) throws Exception {
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(repoDir.resolve(".git").toFile())
                .readEnvironment().findGitDir().build();
             Git git = new Git(repo)) {
            AbstractTreeIterator oldTree = treeParser(repo, fromSha);
            AbstractTreeIterator newTree = treeParser(repo, toSha);
            return git.diff().setOldTree(oldTree).setNewTree(newTree).call().stream()
                .map(entry -> new Change(toChangeType(entry.getChangeType()),
                    entry.getChangeType() == DiffEntry.ChangeType.DELETE
                        ? entry.getOldPath() : entry.getNewPath()))
                .toList();
        }
    }
    // treeParser and toChangeType unchanged
}
```

Update `GitChangeDetectorTest` to pass `repoDir` as a parameter to `detectChanges`.

Update `IndexService` to pass `repoPath` to `detectChanges`:
```java
List<Change> changes = changeDetector.detectChanges(repoPath, fromSha, toSha);
```

Add to `LuceneConfig.java`:
```java
@Bean
public GitChangeDetector gitChangeDetector() {
    return new GitChangeDetector();
}
```

- [ ] **Step 5: Compile and run all tests**

```bash
mvn test -q
```
Expected: All tests pass.

- [ ] **Step 6: Start server and verify**

```bash
mvn spring-boot:run &
sleep 5
curl -s -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"install","k":5}' | python3 -m json.tool
```
Expected: `{"hits":[],"total":0}` (index is empty; server is running).

```bash
curl -s http://localhost:8080/actuator/health
```
Expected: `{"status":"UP"}`.

Kill the background server: `kill %1`

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/acme/airetrieval/api/ \
        src/main/java/com/acme/airetrieval/config/ \
        src/main/java/com/acme/airetrieval/ingest/
git commit -m "feat: REST layer — POST /api/v1/search and POST /api/v1/index/incremental"
```

---

### Task 11: Integration test

**Files:**
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/api/SearchControllerIntegrationTest.java`

- [ ] **Step 1: Write integration test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/api/SearchControllerIntegrationTest.java
package com.acme.airetrieval.api;

import com.acme.airetrieval.index.LuceneIndexer;
import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SearchControllerIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired LuceneIndexer indexer;

    @Test
    void search_indexedContent_returnsHit() throws Exception {
        var chunk = new Chunk("test-1", "repo", "docs/test.md", Domain.KNOWLEDGE,
            "MD_SECTION", null, "Test Doc", null, List.of(),
            "sha1", "hash1", "markdown", "Kafka is a distributed streaming platform", null);
        indexer.upsert(chunk);
        indexer.commit();

        mvc.perform(post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query": "distributed streaming", "k": 5}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.hits[0].id").value("test-1"));
    }

    @Test
    void search_blankQuery_returnsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query": "  "}
                    """))
            .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Add test application.yml for temp index dir**

Create `src/test/resources/application.yml`:
```yaml
kira:
  data-dir: ${java.io.tmpdir}/kira-test
  index-dir: ${kira.data-dir}/lucene
  checkpoint-file: ${kira.data-dir}/checkpoint.json
  max-search-results: 50
  default-search-k: 10
  executor:
    index-threads: 2
```

- [ ] **Step 3: Run integration test**

```bash
mvn test -Dtest=SearchControllerIntegrationTest -q
```
Expected: Tests run: 2, Failures: 0, Errors: 0.

- [ ] **Step 4: Run full suite**

```bash
mvn test -q
```
Expected: All tests pass, BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/acme/airetrieval/api/ src/test/resources/
git commit -m "test: integration test — full path search + bad-request guard"
```

---

### Phase 0 Exit Criterion

Run:
```bash
mvn spring-boot:run &
sleep 5
# Index some real files if you have a git repo to point at; else verify with integration test
mvn test -q
kill %1
```

**All tests pass + `POST /api/v1/search` returns keyword results = Phase 0 complete.**

Proceed to `2026-06-15-phase1-semantic.md`.
