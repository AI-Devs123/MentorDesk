package com.nexusmind.rag.engine;

import com.nexusmind.api.rag.dto.RagIndexDocument;
import com.nexusmind.api.rag.dto.ScoredCandidate;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lucene 双引擎物理管理器（Week 7 切片 1）。
 *
 * <p>核心设计：同一 {@link Document} 内同时写入 {@link TextField}(BM25 词法) 与
 * {@link KnnFloatVectorField}(HNSW 余弦密集向量)，由单一 {@link IndexSearcher} 分别执行两路检索。
 * 本实现相对原始切片稿的三处加固：
 * <ol>
 *   <li><b>向量维度漂移拦截</b>：每个 collection 锁定单一维度，512(bge-small)/1024(bge-m3) 混写会被拒绝，
 *       防止 HNSW 索引静默损坏；</li>
 *   <li><b>NRT 复用</b>：用 {@link SearcherManager} 替代每次搜索的 {@code DirectoryReader.open}，
 *       基于 IndexWriter 的近实时 reader，writer 提交后 {@code maybeRefresh()} 即可见，
 *       避免段重开成为延迟杀手（原 DoD 在 100 QPS 下不成立的根因）；</li>
 *   <li><b>批量提交</b>：新增 {@link #indexDocuments(Collection)} 与 {@link #commit(String)}，
 *       冷资产预热不再每篇一次 fsync。</li>
 * </ol>
 */
@Component
public class LuceneDualEngineManager {

    private static final Logger log = LoggerFactory.getLogger(LuceneDualEngineManager.class);
    private static final String VECTOR_FIELD = "content_vector";

    @Value("${nexusmind.rag.index-dir:./data/lucene_indices}")
    private String indexBaseDir;

    private final Map<String, Directory> directoryMap = new ConcurrentHashMap<>();
    private final Map<String, IndexWriter> writerMap = new ConcurrentHashMap<>();
    private final Map<String, SearcherManager> searcherManagerMap = new ConcurrentHashMap<>();
    // 每个 collection 锁定单一向量维度，防止 512/1024 混写导致 HNSW 索引损坏
    private final Map<String, Integer> collectionDimMap = new ConcurrentHashMap<>();

    private final BM25Similarity bm25Similarity = new BM25Similarity(1.2f, 0.75f);
    private final StandardAnalyzer fallbackAnalyzer = new StandardAnalyzer();

    @PostConstruct
    public void init() {
        log.info("[LuceneDualEngine] 初始化本地 Lucene 双引擎存储底座，根路径 -> {}", indexBaseDir);
    }

    private synchronized void ensureCollection(String collection) throws IOException {
        if (writerMap.containsKey(collection)) {
            return;
        }
        Path path = Paths.get(indexBaseDir, collection).toAbsolutePath().normalize();
        Directory dir = FSDirectory.open(path);
        IndexWriterConfig config = new IndexWriterConfig(fallbackAnalyzer);
        config.setSimilarity(bm25Similarity);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        IndexWriter writer = new IndexWriter(dir, config);
        directoryMap.put(collection, dir);
        writerMap.put(collection, writer);
        // 基于 IndexWriter 构建 NRT SearcherManager：writer 提交后 near-real-time 可见，
        // 彻底避免每次搜索都 DirectoryReader.open 触发段重开（切片1 延迟杀手修复点）
        searcherManagerMap.put(collection, new SearcherManager(writer, true, true, new SearcherFactory()));
        log.info("[LuceneDualEngine] collection '{}' 引擎就绪，索引目录 -> {}", collection, path);
    }

    private void assertDimension(String collection, float[] vector) {
        if (vector == null || vector.length == 0) {
            return;
        }
        int dim = vector.length;
        Integer locked = collectionDimMap.putIfAbsent(collection, dim);
        if (locked != null && locked != dim) {
            throw new IllegalArgumentException(String.format(
                    "向量维度漂移拦截：collection '%s' 已锁定维度 %d，拒绝维度 %d 的文档写入（混写会破坏 HNSW 索引）",
                    collection, locked, dim));
        }
    }

    /**
     * 单条写入（幂等 upsert by doc_id），写完即提交，适合实时单篇入库。
     */
    public void indexDocument(RagIndexDocument doc) throws IOException {
        ensureCollection(doc.collection());
        assertDimension(doc.collection(), doc.vector());
        IndexWriter writer = writerMap.get(doc.collection());
        writer.updateDocument(new Term("doc_id", doc.docId()), buildDocument(doc));
        writer.commit();
    }

    /**
     * 批量写入（单一 collection），仅一次提交，适合 Week4~6 冷资产预热。
     */
    public void indexDocuments(Collection<RagIndexDocument> docs) throws IOException {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        String collection = null;
        IndexWriter writer = null;
        for (RagIndexDocument doc : docs) {
            if (collection == null) {
                collection = doc.collection();
            } else if (!collection.equals(doc.collection())) {
                throw new IllegalArgumentException("批量索引仅支持单一 collection");
            }
            ensureCollection(doc.collection());
            assertDimension(doc.collection(), doc.vector());
            if (writer == null) {
                writer = writerMap.get(doc.collection());
            }
            writer.updateDocument(new Term("doc_id", doc.docId()), buildDocument(doc));
        }
        if (writer != null) {
            writer.commit();
        }
    }

    /**
     * 显式提交指定 collection 的待落盘段（批量写入后调用）。
     */
    public void commit(String collection) throws IOException {
        IndexWriter writer = writerMap.get(collection);
        if (writer != null) {
            writer.commit();
        }
    }

    private Document buildDocument(RagIndexDocument doc) {
        Document luceneDoc = new Document();
        // 1. 唯一标识与系统路由
        luceneDoc.add(new StringField("doc_id", doc.docId(), Field.Store.YES));
        luceneDoc.add(new StringField("collection", doc.collection(), Field.Store.YES));
        // 2. 词法通道：TextField 写入并存储原文本
        luceneDoc.add(new TextField("content", doc.content(), Field.Store.YES));
        // 3. 密集向量通道：KnnFloatVectorField 挂载 COSINE（Lucene 9 内部自建 HNSW 图索引）
        if (doc.vector() != null && doc.vector().length > 0) {
            luceneDoc.add(new KnnFloatVectorField(VECTOR_FIELD, doc.vector(), VectorSimilarityFunction.COSINE));
        }
        // 4. 标量元数据录入（支撑按日期/语言过滤，将来可用 KnnFloatVectorQuery(..., filterQuery) 做子过滤）
        if (doc.metadata() != null) {
            doc.metadata().forEach((k, v) -> {
                if (v != null) {
                    luceneDoc.add(new StringField("meta_" + k, v, Field.Store.YES));
                }
            });
        }
        return luceneDoc;
    }

    /**
     * 稠密向量检索通道 (Dense KNN Search)。
     */
    public List<ScoredCandidate> searchDense(String collection, float[] queryVector, int topK) throws IOException {
        SearcherManager mgr = searcherManagerMap.get(collection);
        if (mgr == null || queryVector == null) {
            return Collections.emptyList();
        }
        mgr.maybeRefresh();
        IndexSearcher searcher = mgr.acquire();
        try {
            Query knnQuery = new KnnFloatVectorQuery(VECTOR_FIELD, queryVector, topK);
            TopDocs topDocs = searcher.search(knnQuery, topK);
            return mapToCandidates(searcher, topDocs, collection);
        } finally {
            mgr.release(searcher);
        }
    }

    /**
     * 稀疏词法检索通道 (Sparse BM25 Search)，Query 由调用方负责分析（如 TermQuery / BooleanQuery）。
     */
    public List<ScoredCandidate> searchSparse(String collection, Query luceneQuery, int topK) throws IOException {
        SearcherManager mgr = searcherManagerMap.get(collection);
        if (mgr == null) {
            return Collections.emptyList();
        }
        mgr.maybeRefresh();
        IndexSearcher searcher = mgr.acquire();
        try {
            searcher.setSimilarity(bm25Similarity);
            TopDocs topDocs = searcher.search(luceneQuery, topK);
            return mapToCandidates(searcher, topDocs, collection);
        } finally {
            mgr.release(searcher);
        }
    }

    private List<ScoredCandidate> mapToCandidates(IndexSearcher searcher, TopDocs topDocs, String collection)
            throws IOException {
        List<ScoredCandidate> list = new ArrayList<>();
        int rank = 1;
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            Document doc = searcher.doc(scoreDoc.doc);
            Map<String, String> meta = new HashMap<>();
            for (IndexableField f : doc.getFields()) {
                if (f.name().startsWith("meta_")) {
                    meta.put(f.name().substring(5), f.stringValue());
                }
            }
            list.add(new ScoredCandidate(
                    doc.get("doc_id"),
                    collection,
                    doc.get("content"),
                    scoreDoc.score,
                    rank++,
                    meta));
        }
        return list;
    }

    @PreDestroy
    public void close() {
        searcherManagerMap.values().forEach(m -> {
            try {
                m.close();
            } catch (IOException ignored) {
            }
        });
        writerMap.values().forEach(w -> {
            try {
                w.close();
            } catch (IOException ignored) {
            }
        });
        directoryMap.values().forEach(d -> {
            try {
                d.close();
            } catch (IOException ignored) {
            }
        });
    }
}
