package org.icij.datashare.text.nlp;

import com.google.inject.Inject;
import org.icij.datashare.Neo4jNamedEntityRepository;
import org.icij.datashare.com.Message;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.icij.datashare.com.Message.Field.*;

public class NlpConsumer implements DatashareListener {
    private final Indexer indexer;
    private final BlockingQueue<Message> messageQueue;
    private final AbstractPipeline nlpPipeline;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Neo4jNamedEntityRepository repository;

    @Inject
    public NlpConsumer(AbstractPipeline pipeline, Indexer indexer, BlockingQueue<Message> messageQueue) {
        this.indexer = indexer;
        this.messageQueue = messageQueue;
        this.nlpPipeline = pipeline;
        try {
            this.repository = new Neo4jNamedEntityRepository();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void run() {
        boolean exitAsked = false;
        while (! exitAsked) {
            try {
                Message message = messageQueue.poll(30, TimeUnit.SECONDS);
                if (message != null) {
                    switch (message.type) {
                        case EXTRACT_NLP:
                            findNamedEntities(message.content.get(INDEX_NAME), message.content.get(DOC_ID), message.content.get(R_ID));
                            break;
                        case SHUTDOWN:
                            exitAsked = true;
                            break;
                        default:
                            logger.info("ignore {}", message);
                    }
                    synchronized (messageQueue) {
                        if (messageQueue.isEmpty()) {
                            messageQueue.notify();
                        }
                    }
                }
            } catch (Throwable e) {
                logger.warn("error in consumer main loop", e);
            }
        }
        logger.info("exiting main loop");
    }

    void findNamedEntities(final String indexName, final String id, final String routing) throws InterruptedException {
        try {
            Document doc = indexer.get(indexName, id, routing);
            if (doc != null) {
                logger.info("extracting {} entities for document {}", nlpPipeline.getType(), doc.getId());
                if (nlpPipeline.initialize(doc.getLanguage())) {
                    Annotations annotations = nlpPipeline.process(doc.getContent(), doc.getId(), doc.getLanguage());
                    List<NamedEntity> namedEntities = NamedEntity.allFrom(doc.getContent(), annotations);
                    indexer.bulkAdd(indexName, nlpPipeline.getType(), namedEntities, doc);

                    try {
                        for (NamedEntity ne : namedEntities) {
                            repository.create(ne);
                        }
                    } catch (Exception ex) {
                        logger.error("error with Neo4j create", ex);
                    }
                    logger.info("added {} named entities to document {}", namedEntities.size(), doc.getId());
                    nlpPipeline.terminate(doc.getLanguage());
                }
            } else {
                logger.warn("no document found in index with id " + id);
            }
        } catch (IOException e) {
            logger.error("cannot extract entities of doc " + id, e);
        }
    }
}
