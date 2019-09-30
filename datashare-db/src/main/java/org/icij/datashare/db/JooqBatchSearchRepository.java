package org.icij.datashare.db;

import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearch.State;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchResult;
import org.icij.datashare.text.Document;
import org.icij.datashare.user.User;
import org.jooq.*;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.io.Closeable;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.IntStream;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.*;
import static org.icij.datashare.batch.BatchSearchRepository.WebQuery.DEFAULT_SORT_FIELD;
import static org.icij.datashare.text.Project.project;
import static org.jooq.impl.DSL.*;

public class JooqBatchSearchRepository implements BatchSearchRepository {
    private static final String BATCH_SEARCH = "batch_search";
    private static final String BATCH_SEARCH_QUERY = "batch_search_query";
    private static final String BATCH_SEARCH_RESULT = "batch_search_result";
    private final DataSource dataSource;
    private final SQLDialect dialect;

    JooqBatchSearchRepository(final DataSource dataSource, final SQLDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @Override
    public boolean save(final User user, final BatchSearch batchSearch) {
        return DSL.using(dataSource, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            inner.insertInto(table(BATCH_SEARCH), field("uuid"), field("name"), field("description"), field("user_id"), field("prj_id"), field("batch_date"), field("state"), field("published")).
                    values(batchSearch.uuid, batchSearch.name, batchSearch.description, user.id, batchSearch.project.getId(), new Timestamp(batchSearch.getDate().getTime()), batchSearch.state.name(), batchSearch.published?1:0).execute();
            InsertValuesStep3<Record, Object, Object, Object> insertQuery =
                    inner.insertInto(table(BATCH_SEARCH_QUERY), field("search_uuid"), field("query"), field("query_number"));
            List<String> queries = new ArrayList<>(batchSearch.queries.keySet());
            IntStream.range(0, queries.size()).forEach(i -> insertQuery.values(batchSearch.uuid, queries.get(i), i));
            return insertQuery.execute() > 0;
        });
    }

    @Override
    public boolean saveResults(String batchSearchId, String query, List<Document> documents) {
        DSLContext create = DSL.using(dataSource, dialect);
        InsertValuesStep9<Record, Object, Object, Object, Object, Object, Object, Object, Object, Object> insertQuery =
                create.insertInto(table(BATCH_SEARCH_RESULT), field("search_uuid"), field("query"), field("doc_nb"),
                        field("doc_id"), field("root_id"), field("doc_name"), field("creation_date"), field("content_type"), field("content_length"));
        IntStream.range(0, documents.size()).forEach(i -> insertQuery.values(batchSearchId, query, i,
                documents.get(i).getId(), documents.get(i).getRootDocument(), documents.get(i).getPath().getFileName().toString(),
                documents.get(i).getCreationDate() == null ? val((Timestamp)null):
                        new Timestamp(documents.get(i).getCreationDate().getTime()),
                documents.get(i).getContentType(), documents.get(i).getContentLength()));
        return insertQuery.execute() > 0;
    }

    @Override
    public boolean setState(String batchSearchId, State state) {
        DSLContext create = DSL.using(dataSource, dialect);
        return create.update(table(BATCH_SEARCH)).set(field("state"), state.name()).where(field("uuid").eq(batchSearchId)).execute() > 0;
    }

    @Override
    public boolean deleteAll(User user) {
        return DSL.using(dataSource, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            inner.deleteFrom(table(BATCH_SEARCH_QUERY)).where(field("search_uuid").
                    in(select(field("uuid")).from(table(BATCH_SEARCH)).where(field("user_id").eq(user.id)))).
                    execute();
            inner.deleteFrom(table(BATCH_SEARCH_RESULT)).where(field("search_uuid").
                    in(select(field("uuid")).from(table(BATCH_SEARCH)).where(field("user_id").eq(user.id)))).
                    execute();
            return inner.deleteFrom(table(BATCH_SEARCH)).where(field("user_id").eq(user.id)).execute() > 0;
        });
    }

    @Override
    public boolean delete(User user, String batchId) {
        return DSL.using(dataSource, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            inner.deleteFrom(table(BATCH_SEARCH_QUERY)).where(field("search_uuid").eq(batchId)).execute();
            inner.deleteFrom(table(BATCH_SEARCH_RESULT)).where(field("search_uuid").eq(batchId)).execute();
            return inner.deleteFrom(table(BATCH_SEARCH)).where(field("user_id").eq(user.id)).
                    and(field("uuid").eq(batchId)).execute() > 0;
        });
    }

    @Override
    public List<BatchSearch> get(final User user) {
        return mergeBatchSearches(
                createBatchSearchWithQueriesSelectStatement(DSL.using(dataSource, dialect)).
                where(field(BATCH_SEARCH + ".user_id").eq(user.id).
                        or(field(name(BATCH_SEARCH, "published")).greaterThan(0))).
                        orderBy(field(BATCH_SEARCH + ".batch_date").desc(), field(BATCH_SEARCH_QUERY + ".query_number")).
                fetch().stream().map(this::createBatchSearchFrom).collect(toList()));
    }

    @Override
    public BatchSearch get(User user, String batchId) {
        return mergeBatchSearches(
                createBatchSearchWithQueriesSelectStatement(DSL.using(dataSource, dialect)).
                        where(field("uuid").eq(batchId)).
                        fetch().stream().map(this::createBatchSearchFrom).collect(toList())).get(0);
    }

    @Override
    public List<BatchSearch> getQueued() {
        return mergeBatchSearches(
                createBatchSearchWithQueriesSelectStatement(DSL.using(dataSource, dialect)).
                where(field(BATCH_SEARCH + ".state").eq(State.QUEUED.name())).
                        orderBy(field(BATCH_SEARCH + ".batch_date").desc(), field(BATCH_SEARCH_QUERY + ".query_number")).
                fetch().stream().map(this::createBatchSearchFrom).collect(toList()));
    }

    @Override
    public List<SearchResult> getResults(final User user, String batchSearchId) {
        return getResults(user, batchSearchId, new WebQuery(0, 0));
    }

    @Override
    public List<SearchResult> getResults(User user, String batchSearchId, WebQuery webQuery) {
        DSLContext create = DSL.using(dataSource, dialect);
        SelectConditionStep<Record> query = create.select().from(table(BATCH_SEARCH_RESULT)).
                join(BATCH_SEARCH).on(field(BATCH_SEARCH + ".uuid").equal(field(BATCH_SEARCH_RESULT + ".search_uuid"))).
                where(field("search_uuid").eq(batchSearchId));
        if (webQuery.hasFilteredQueries()) query.and(field("query").in(webQuery.queries));
        if (webQuery.isSorted()) {
            query.orderBy(field(webQuery.sort + " " + webQuery.order));
        } else {
            query.orderBy(field("query"), field(DEFAULT_SORT_FIELD));
        }
        if (webQuery.size > 0) query.limit(webQuery.size);
        if (webQuery.from > 0) query.offset(webQuery.from);

        return query.fetch().stream().map(r -> createSearchResult(user, r)).collect(toList());
    }

    private List<BatchSearch> mergeBatchSearches(final List<BatchSearch> flatBatchSearches) {
        Map<String, List<BatchSearch>> collect = flatBatchSearches.stream().collect(groupingBy(bs -> bs.uuid));
        return collect.values().stream().map(batchSearches ->
                new BatchSearch(batchSearches.get(0).uuid, batchSearches.get(0).project, batchSearches.get(0).name, batchSearches.get(0).description,
                        batchSearches.stream().map(bs -> bs.queries.entrySet()).flatMap(Collection::stream).collect(
                                toMap(Map.Entry::getKey, Map.Entry::getValue,
                                        (u,v) -> { throw new IllegalStateException(String.format("Duplicate key %s", u)); },
                                        LinkedHashMap::new)),
                        batchSearches.get(0).getDate(),
                        batchSearches.get(0).state, batchSearches.get(0).nbResults, batchSearches.get(0).published)).
                sorted(comparing(BatchSearch::getDate).reversed()).collect(toList());
    }

    private SelectJoinStep<Record13<Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, Object>>
    createBatchSearchWithQueriesSelectStatement(DSLContext create) {
        Field<Object> resultCount = create.selectCount().from(table(BATCH_SEARCH_RESULT)).
                where(field(name(BATCH_SEARCH_RESULT, "search_uuid")).
                        equal(field(name(BATCH_SEARCH, "uuid")))).asField("count");
        String countByQueryTableName = "countByQuery";
        String hasResultField = "has_result";
        String queryResultsField = "query_results";
        Table<?> countByQueryDerivedTable = create.select(field(name(BATCH_SEARCH_QUERY, "query")), field(name(BATCH_SEARCH_RESULT, "query")).as(hasResultField), count().as(queryResultsField)).
                from(table(BATCH_SEARCH_QUERY)).
                leftJoin(BATCH_SEARCH_RESULT).on(field(name(BATCH_SEARCH_RESULT, "query")).eq(field(name(BATCH_SEARCH_QUERY, "query")))).
                groupBy(field(name(BATCH_SEARCH_QUERY, "query")), field(hasResultField)).
                asTable(countByQueryTableName);
        return create.select(field("uuid"), field("name"), field("description"), field("user_id"),
                field("prj_id"), field("batch_date"), field("state"),
                field("query_number"), field(name(BATCH_SEARCH_QUERY, "query")),
                field(name(BATCH_SEARCH, "published")),
                field(name(countByQueryTableName, hasResultField)),
                field(name(countByQueryTableName, queryResultsField)), resultCount).
                from(table(BATCH_SEARCH).
                        join(BATCH_SEARCH_QUERY).
                        on(field(BATCH_SEARCH + ".uuid").
                                eq(field(BATCH_SEARCH_QUERY + ".search_uuid"))).
                        join(countByQueryDerivedTable).on(field(name(countByQueryTableName, "query")).
                                eq(field(name(BATCH_SEARCH_QUERY, "query"))
                )));
    }

    private BatchSearch createBatchSearchFrom(final Record record) {
        String hasResult = record.getValue("has_result", String.class);
        Integer query_results = hasResult == null ? 0:record.getValue("query_results", Integer.class);
        return new BatchSearch(record.get("uuid", String.class).trim(),
                project(record.getValue("prj_id", String.class)),
                record.getValue("name", String.class),
                record.getValue("description", String.class),
                new LinkedHashMap<String, Integer>() {{
                    put(record.getValue("query", String.class), query_results);}},
                new Date(record.get("batch_date", Timestamp.class).getTime()),
                State.valueOf(record.get("state", String.class)),
                record.get("count", Integer.class),
                record.get("published", Integer.class) > 0);
    }

    private SearchResult createSearchResult(final User actualUser, final Record record) {
        String owner = record.get("user_id", String.class);
        if (!actualUser.id.equals(owner))
            throw new UnauthorizedUserException(record.get("uuid", String.class), owner, actualUser.id);
        Timestamp creationDate = record.get("creation_date", Timestamp.class);
        return new SearchResult(record.get(field("query"), String.class),
                record.get(field("doc_id"), String.class),
                record.getValue("root_id", String.class),
                record.getValue("doc_name", String.class),
                creationDate == null ? null: new Date(creationDate.getTime()),
                record.getValue("content_type", String.class),
                record.getValue("content_length", Long.class),
                record.get("doc_nb", Integer.class));
    }

    @Override
    public void close() throws IOException {
        if (dataSource instanceof Closeable) {
            ((Closeable) dataSource).close();
        }
    }

    public static class UnauthorizedUserException extends RuntimeException {
        public UnauthorizedUserException(String searchId, String owner, String actualUser) {
            super("user " + actualUser + " requested results for search " + searchId + " that belongs to user " + owner);
        }
    }
}
