package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.RedisUserDocumentQueue;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchExtractedStreamer;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.extract.QueueFilterBuilder;
import org.icij.task.DefaultTask;
import org.icij.task.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * filters the document queue with extracted docs
 * and removes duplicates from the queue
 */
public class FilterTask extends DefaultTask<Integer> implements UserTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final String projectName;
    private Indexer indexer;
    private User user;
    private final RedisUserDocumentQueue queue;

    @Inject
    public FilterTask(final Indexer indexer, final PropertiesProvider propertiesProvider, @Assisted User user) {
        this.projectName = propertiesProvider.get("projectName").orElse("local-datashare");
        this.queue = new RedisUserDocumentQueue(user, Options.from(propertiesProvider.getProperties()));
        this.indexer = indexer;
        this.user = user;
    }

    @Override
    public Integer call() throws Exception {
        if (queue.size() == 0) {
            logger.info("filter empty queue {} nothing to do", queue.getName());
            return 0;
        }
        int duplicates = queue.removeDuplicatePaths();
        logger.info("removed {} duplicate paths in queue {}", duplicates, queue.getName());
        int initialSize = queue.size();
        RedisUserDocumentQueue filteredQueue = (RedisUserDocumentQueue)new QueueFilterBuilder()
                .filter(queue)
                .with(new ElasticsearchExtractedStreamer(indexer, projectName))
                .execute();
        queue.delete();
        logger.info("delete queue {}", queue.getName());
        if (filteredQueue.size() > 0) {
            logger.info("rename queue {} to {}", filteredQueue.getName(), queue.getName());
            filteredQueue.rename(queue.getName());
        }
        int extracted = initialSize - filteredQueue.size();
        logger.info("removed {} already extracted documents", extracted, filteredQueue.getName());
        queue.close();
        filteredQueue.close();
        indexer.close();
        return duplicates + extracted;
    }

    @Override
    public User getUser() {
        return user;
    }
}
