package com.acme.airetrieval.index;

import com.acme.airetrieval.ingest.model.Chunk;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.MMapDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LuceneIndexer implements AutoCloseable {
    private final StandardAnalyzer analyzer = new StandardAnalyzer();
    private final IndexWriter writer;

    public LuceneIndexer(Path indexDir) throws IOException {
        Files.createDirectories(indexDir);
        var config = new IndexWriterConfig(analyzer).setRAMBufferSizeMB(256);
        writer = new IndexWriter(MMapDirectory.open(indexDir), config);
    }

    public void upsert(Chunk chunk) throws IOException {
        Document doc = new Document();
        add(doc, "id", chunk.id());
        add(doc, "repo", chunk.repo());
        add(doc, "branch", chunk.branch());
        add(doc, "path", chunk.path());
        add(doc, "domain", chunk.domain() == null ? null : chunk.domain().name());
        add(doc, "type", chunk.type());
        add(doc, "fqn", chunk.fqn());
        if (chunk.fqn() != null) {
            String fqn = chunk.fqn();
            int sep = Math.max(Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('#')), fqn.lastIndexOf('/'));
            String simple = sep >= 0 ? fqn.substring(sep + 1) : fqn;
            int paren = simple.indexOf('(');
            if (paren >= 0) simple = simple.substring(0, paren);
            doc.add(new StringField("fqn_simple", simple.toLowerCase(), Field.Store.NO));
        }
        add(doc, "title", chunk.title());
        add(doc, "section", chunk.section());
        add(doc, "git_sha", chunk.gitSha());
        add(doc, "content_hash", chunk.contentHash());
        add(doc, "lang", chunk.lang());
        if (chunk.symbols() != null) {
            for (String symbol : chunk.symbols()) {
                if (symbol != null && !symbol.isBlank()) {
                    doc.add(new StringField("symbol", symbol, Field.Store.NO));
                }
            }
        }
        doc.add(new TextField("text", chunk.text() == null ? "" : chunk.text(), Field.Store.YES));
        if (chunk.vector() != null) {
            doc.add(new KnnFloatVectorField("vector", chunk.vector(), VectorSimilarityFunction.COSINE));
        }
        writer.updateDocument(new Term("id", chunk.id()), doc);
    }

    public void deleteByPath(String path) throws IOException {
        writer.deleteDocuments(new Term("path", path));
    }

    public void deleteByPathAndBranch(String path, String branch) throws IOException {
        if (branch == null) {
            deleteByPath(path);
            return;
        }
        var query = new BooleanQuery.Builder()
            .add(new TermQuery(new Term("path", path)), BooleanClause.Occur.MUST)
            .add(new TermQuery(new Term("branch", branch)), BooleanClause.Occur.MUST)
            .build();
        writer.deleteDocuments(query);
    }

    public void commit() throws IOException {
        writer.commit();
    }

    public IndexWriter getWriter() {
        return writer;
    }

    private static void add(Document doc, String name, String value) {
        if (value != null) doc.add(new StringField(name, value, Field.Store.YES));
    }

    @Override
    public void close() throws IOException {
        writer.close();
        analyzer.close();
    }
}
