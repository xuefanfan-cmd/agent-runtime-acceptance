package acceptance.knowledgebase;

import com.openjiuwen.core.retrieval.embedding.HashEmbedding;

public final class CoreOnlyConsumer {
    private CoreOnlyConsumer() {
    }

    public static void main(String[] args) throws Exception {
        HashEmbedding embedding = new HashEmbedding(16, 32);
        if (embedding.getClass().getName().isBlank()) {
            throw new IllegalStateException("Core embedding was not created");
        }
        try {
            Class.forName("com.openjiuwen.retrieval.SimpleKnowledgeBase");
            throw new IllegalStateException("Retrieval implementation leaked into the Core-only classpath");
        } catch (ClassNotFoundException expected) {
            System.out.println("RETRIEVAL_CLASS=absent");
        }
        System.out.println("STATUS=CORE_ONLY_OK");
    }
}

