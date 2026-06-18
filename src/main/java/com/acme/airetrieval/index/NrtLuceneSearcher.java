package com.acme.airetrieval.index;

import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.index.model.SearchHit;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.queryparser.classic.QueryParserBase;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.WildcardQuery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class NrtLuceneSearcher implements AutoCloseable {
    private final SearcherManager manager;
    private final StandardAnalyzer analyzer = new StandardAnalyzer();

    public NrtLuceneSearcher(IndexWriter writer) throws IOException {
        manager = new SearcherManager(writer, new SearcherFactory());
    }

    public void maybeReopen() throws IOException {
        manager.maybeRefresh();
    }

    public int numDocs() throws IOException {
        IndexSearcher searcher = manager.acquire();
        try {
            return searcher.getIndexReader().numDocs();
        } finally {
            manager.release(searcher);
        }
    }

    public List<SearchHit> bm25(String text, SearchFilter filter, int k) throws IOException {
        IndexSearcher searcher = manager.acquire();
        try {
            Query query = new QueryParser("text", analyzer).parse(QueryParserBase.escape(text == null ? "" : text));
            return toHits(searcher, searcher.search(applyFilter(query, filter), k).scoreDocs);
        } catch (Exception e) {
            Query query = new TermQuery(new Term("text", text == null ? "" : text.toLowerCase()));
            return toHits(searcher, searcher.search(applyFilter(query, filter), k).scoreDocs);
        } finally {
            manager.release(searcher);
        }
    }

    public List<SearchHit> knn(float[] vector, SearchFilter filter, int k) throws IOException {
        IndexSearcher searcher = manager.acquire();
        try {
            Query query = new KnnFloatVectorQuery("vector", vector, k, buildFilter(filter));
            return toHits(searcher, searcher.search(query, k).scoreDocs);
        } finally {
            manager.release(searcher);
        }
    }

    public Optional<String> getContentHash(String id) throws IOException {
        IndexSearcher searcher = manager.acquire();
        try {
            var hits = searcher.search(new TermQuery(new Term("id", id)), 1);
            if (hits.scoreDocs.length == 0) return Optional.empty();
            return Optional.ofNullable(searcher.storedFields().document(hits.scoreDocs[0].doc).get("content_hash"));
        } finally {
            manager.release(searcher);
        }
    }

    public Optional<String> findByFqn(String fqn) throws IOException {
        IndexSearcher searcher = manager.acquire();
        try {
            var hits = searcher.search(new TermQuery(new Term("fqn", fqn)), 1);
            if (hits.scoreDocs.length == 0) return Optional.empty();
            return Optional.ofNullable(searcher.storedFields().document(hits.scoreDocs[0].doc).get("text"));
        } finally {
            manager.release(searcher);
        }
    }

    public List<SearchHit> searchByNameFragment(String partial, String type, int k) throws IOException {
        IndexSearcher searcher = manager.acquire();
        try {
            String lower = partial.toLowerCase();
            BooleanQuery mainQuery = new BooleanQuery.Builder()
                .add(new WildcardQuery(new Term("fqn_simple", lower + "*")), BooleanClause.Occur.SHOULD)
                .add(new TermQuery(new Term("fqn", partial)), BooleanClause.Occur.SHOULD)
                .build();
            SearchFilter filter = new SearchFilter(null, null, type, null, null);
            return toHits(searcher, searcher.search(applyFilter(mainQuery, filter), k).scoreDocs);
        } finally {
            manager.release(searcher);
        }
    }

    public Optional<String> findTitleById(String id) throws IOException {
        IndexSearcher searcher = manager.acquire();
        try {
            var hits = searcher.search(new TermQuery(new Term("id", id)), 1);
            if (hits.scoreDocs.length == 0) return Optional.empty();
            Document doc = searcher.storedFields().document(hits.scoreDocs[0].doc);
            String title = doc.get("title");
            return Optional.ofNullable(title != null ? title : doc.get("fqn"));
        } finally {
            manager.release(searcher);
        }
    }

    public List<SearchHit> findByTypeAndRepo(String type, String repo, int limit) throws IOException {
        IndexSearcher searcher = manager.acquire();
        try {
            BooleanQuery.Builder b = new BooleanQuery.Builder()
                .add(new TermQuery(new Term("type", type)), BooleanClause.Occur.MUST);
            if (repo != null) {
                b.add(new TermQuery(new Term("repo", repo)), BooleanClause.Occur.FILTER);
            }
            return toHits(searcher, searcher.search(b.build(), limit).scoreDocs);
        } finally {
            manager.release(searcher);
        }
    }

    private List<SearchHit> toHits(IndexSearcher searcher, ScoreDoc[] docs) throws IOException {
        List<SearchHit> hits = new ArrayList<>();
        for (ScoreDoc scoreDoc : docs) {
            Document doc = searcher.storedFields().document(scoreDoc.doc);
            hits.add(SearchHit.fromDocument(doc, scoreDoc.score));
        }
        return hits;
    }

    private Query applyFilter(Query base, SearchFilter filter) {
        Query filterQuery = buildFilter(filter);
        if (filterQuery == null) return base;
        return new BooleanQuery.Builder().add(base, BooleanClause.Occur.MUST).add(filterQuery, BooleanClause.Occur.FILTER).build();
    }

    private Query buildFilter(SearchFilter filter) {
        if (filter == null) return null;
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        boolean any = false;
        if (filter.repo() != null) { b.add(new TermQuery(new Term("repo", filter.repo())), BooleanClause.Occur.FILTER); any = true; }
        if (filter.domain() != null) { b.add(new TermQuery(new Term("domain", filter.domain())), BooleanClause.Occur.FILTER); any = true; }
        if (filter.type() != null) { b.add(new TermQuery(new Term("type", filter.type())), BooleanClause.Occur.FILTER); any = true; }
        if (filter.path() != null) { b.add(new TermQuery(new Term("path", filter.path())), BooleanClause.Occur.FILTER); any = true; }
        if (filter.branch() != null) { b.add(new TermQuery(new Term("branch", filter.branch())), BooleanClause.Occur.FILTER); any = true; }
        if (filter.symbol() != null) { b.add(new TermQuery(new Term("symbol", filter.symbol())), BooleanClause.Occur.FILTER); any = true; }
        return any ? b.build() : null;
    }

    @Override
    public void close() throws IOException {
        manager.close();
        analyzer.close();
    }
}
