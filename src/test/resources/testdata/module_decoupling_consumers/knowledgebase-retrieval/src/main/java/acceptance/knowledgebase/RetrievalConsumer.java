package acceptance.knowledgebase;

import com.openjiuwen.core.retrieval.embedding.HashEmbedding;
import com.openjiuwen.core.retrieval.common.Document;
import com.openjiuwen.core.retrieval.common.RetrievalResult;
import com.openjiuwen.core.retrieval.vector_store.InMemoryVectorStore;
import com.openjiuwen.retrieval.SimpleKnowledgeBase;
import com.openjiuwen.retrieval.common.KnowledgeBaseConfig;
import com.openjiuwen.retrieval.common.RetrievalConfig;
import com.openjiuwen.retrieval.indexing.indexer.InMemoryIndexer;
import com.openjiuwen.retrieval.indexing.processor.chunker.CharChunker;

import java.util.List;

public final class RetrievalConsumer {
    private static final String COMPONENT =
            "com.openjiuwen.retrieval.workflow.component.resource.KnowledgeRetrievalComponent";

    private RetrievalConsumer() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "flow";
        String canary = args.length > 1 ? args[1] : "knowledgebase-canary";
        if ("workflow-component".equals(mode)) {
            Class<?> component = Class.forName(COMPONENT);
            System.out.println("COMPONENT_CLASS=" + component.getName());
            System.out.println("STATUS=WORKFLOW_COMPONENT_OK");
            return;
        }
        if (!"flow".equals(mode)) {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }

        KnowledgeBaseConfig config = new KnowledgeBaseConfig("kb-" + canary);
        HashEmbedding embedding = new HashEmbedding(32, 256);
        InMemoryVectorStore vectorStore = new InMemoryVectorStore("collection-" + canary);
        InMemoryIndexer indexer = new InMemoryIndexer(vectorStore);
        SimpleKnowledgeBase knowledgeBase = new SimpleKnowledgeBase(config, vectorStore, embedding, null,
                new CharChunker(512, 64), indexer, null, null);
        knowledgeBase.addDocuments(List.of(
                new Document("doc-" + canary, "OpenJiuwen local retrieval marker " + canary),
                new Document("doc-control", "Unrelated control document")));

        RetrievalConfig retrievalConfig = new RetrievalConfig();
        retrievalConfig.setTopK(2);
        List<RetrievalResult> results = knowledgeBase.retrieve(canary, retrievalConfig);
        boolean found = results.stream().anyMatch(result -> result.getText() != null
                && result.getText().contains(canary));
        if (!found) {
            throw new IllegalStateException("Local retrieval did not return the canary");
        }
        System.out.println("CANARY=" + canary);
        System.out.println("RESULT_COUNT=" + results.size());
        System.out.println("STATUS=RETRIEVAL_FLOW_OK");
    }
}
