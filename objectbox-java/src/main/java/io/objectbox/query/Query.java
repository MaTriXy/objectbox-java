package io.objectbox.query;

import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import io.objectbox.Box;
import io.objectbox.BoxStore;
import io.objectbox.InternalAccess;
import io.objectbox.Property;
import io.objectbox.annotation.apihint.Beta;
import io.objectbox.internal.CallWithHandle;
import io.objectbox.reactive.DataObserver;
import io.objectbox.reactive.DataSubscriptionList;
import io.objectbox.reactive.SubscriptionBuilder;
import io.objectbox.relation.RelationInfo;
import io.objectbox.relation.ToOne;

/**
 * A repeatable query returning entities.
 *
 * @param <T> The entity class the query will return results for.
 * @author Markus
 * @see QueryBuilder
 */
@Beta
public class Query<T> {

    native void nativeDestroy(long handle);

    native Object nativeFindFirst(long handle, long cursorHandle);

    native Object nativeFindUnique(long handle, long cursorHandle);

    native List nativeFind(long handle, long cursorHandle, long offset, long limit);

    native long[] nativeFindKeysUnordered(long handle, long cursorHandle);

    native long nativeCount(long handle, long cursorHandle);

    native long nativeSum(long handle, long cursorHandle, int propertyId);

    native double nativeSumDouble(long handle, long cursorHandle, int propertyId);

    native long nativeMax(long handle, long cursorHandle, int propertyId);

    native double nativeMaxDouble(long handle, long cursorHandle, int propertyId);

    native long nativeMin(long handle, long cursorHandle, int propertyId);

    native double nativeMinDouble(long handle, long cursorHandle, int propertyId);

    native double nativeAvg(long handle, long cursorHandle, int propertyId);

    native long nativeRemove(long handle, long cursorHandle);

    native void nativeSetParameter(long handle, int propertyId, String parameterAlias, String value);

    native void nativeSetParameter(long handle, int propertyId, String parameterAlias, long value);

    native void nativeSetParameters(long handle, int propertyId, String parameterAlias, long value1,
                                    long value2);

    native void nativeSetParameter(long handle, int propertyId, String parameterAlias, double value);

    native void nativeSetParameters(long handle, int propertyId, String parameterAlias, double value1,
                                    double value2);

    private final Box<T> box;
    private final BoxStore store;
    private final boolean hasOrder;
    private final QueryPublisher<T> publisher;
    private final List<EagerRelation> eagerRelations;
    private final QueryFilter<T> filter;
    private final Comparator<T> comparator;
    private final int queryAttempts;
    private final int initialRetryBackOffInMs = 10;

    long handle;

    Query(Box<T> box, long queryHandle, boolean hasOrder, List<EagerRelation> eagerRelations, QueryFilter<T> filter,
          Comparator<T> comparator) {
        this.box = box;
        store = box.getStore();
        queryAttempts = store.internalQueryAttempts();
        handle = queryHandle;
        this.hasOrder = hasOrder;
        publisher = new QueryPublisher<>(this, box);
        this.eagerRelations = eagerRelations;
        this.filter = filter;
        this.comparator = comparator;
    }

    @Override
    protected void finalize() throws Throwable {
        close();
        super.finalize();
    }

    /**
     * If possible, try to close the query once you are done with it to reclaim resources immediately.
     */
    public synchronized void close() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    /**
     * Find the first Object matching the query.
     */
    @Nullable
    public T findFirst() {
        ensureNoFilterNoComparator();
        return store.callInReadTxWithRetry(new Callable<T>() {
            @Override
            public T call() {
                @SuppressWarnings("unchecked")
                T entity = (T) nativeFindFirst(handle, InternalAccess.getActiveTxCursorHandle(box));
                resolveEagerRelation(entity);
                return entity;
            }
        }, queryAttempts, initialRetryBackOffInMs, true);
    }

    private void ensureNoFilterNoComparator() {
        if (filter != null) {
            throw new UnsupportedOperationException("Does not yet work with a filter yet. " +
                    "At this point, only find() and forEach() are supported with filters.");
        }
        ensureNoComparator();
    }

    private void ensureNoComparator() {
        if (comparator != null) {
            throw new UnsupportedOperationException("Does not yet work with a sorting comparator yet. " +
                    "At this point, only find() is supported with sorting comparators.");
        }
    }

    /**
     * Find the unique Object matching the query.
     *
     * @throws io.objectbox.exception.DbException if result was not unique
     */
    @Nullable
    public T findUnique() {
        ensureNoFilterNoComparator();
        return store.callInReadTxWithRetry(new Callable<T>() {
            @Override
            public T call() {
                @SuppressWarnings("unchecked")
                T entity = (T) nativeFindUnique(handle, InternalAccess.getActiveTxCursorHandle(box));
                resolveEagerRelation(entity);
                return entity;
            }
        }, queryAttempts, initialRetryBackOffInMs, true);
    }

    /**
     * Find all Objects matching the query.
     */
    @Nonnull
    public List<T> find() {
        return store.callInReadTxWithRetry(new Callable<List<T>>() {
            @Override
            public List<T> call() throws Exception {
                long cursorHandle = InternalAccess.getActiveTxCursorHandle(box);
                List<T> entities = nativeFind(Query.this.handle, cursorHandle, 0, 0);
                if (filter != null) {
                    Iterator<T> iterator = entities.iterator();
                    while (iterator.hasNext()) {
                        T entity = iterator.next();
                        if (!filter.keep(entity)) {
                            iterator.remove();
                        }
                    }
                }
                resolveEagerRelations(entities);
                if (comparator != null) {
                    Collections.sort(entities, comparator);
                }
                return entities;
            }
        }, queryAttempts, initialRetryBackOffInMs, true);
    }

    /**
     * Find all Objects matching the query between the given offset and limit. This helps with pagination.
     */
    @Nonnull
    public List<T> find(final long offset, final long limit) {
        ensureNoFilterNoComparator();
        return store.callInReadTxWithRetry(new Callable<List<T>>() {
            @Override
            public List<T> call() {
                long cursorHandle = InternalAccess.getActiveTxCursorHandle(box);
                List entities = nativeFind(handle, cursorHandle, offset, limit);
                resolveEagerRelations(entities);
                return entities;
            }
        }, queryAttempts, initialRetryBackOffInMs, true);
    }

    /**
     * Very efficient way to get just the IDs without creating any objects. IDs can later be used to lookup objects
     * (lookups by ID are also very efficient in ObjectBox).
     * <p>
     * Note: a filter set with {@link QueryBuilder#filter} will be silently ignored!
     */
    @Nonnull
    public long[] findIds() {
        if (hasOrder) {
            throw new UnsupportedOperationException("This method is currently only available for unordered queries");
        }
        return box.internalCallWithReaderHandle(new CallWithHandle<long[]>() {
            @Override
            public long[] call(long cursorHandle) {
                return nativeFindKeysUnordered(handle, cursorHandle);
            }
        });
    }

    /**
     * Find all Objects matching the query without actually loading the Objects. See @{@link LazyList} for details.
     */
    public LazyList<T> findLazy() {
        ensureNoFilterNoComparator();
        return new LazyList<>(box, findIds(), false);
    }

    /**
     * Emits query results one by one to the given consumer (synchronously).
     * Once this method returns, the consumer will have received all result object).
     * It "streams" each object from the database to the consumer, which is very memory efficient.
     * Because this is run in a read transaction, the consumer gets a consistent view on the data.
     * Like {@link #findLazy()}, this method can be used for a high amount of data.
     * <p>
     * Note: because the consumer is called within a read transaction it may not write to the database.
     */
    public void forEach(final QueryConsumer<T> consumer) {
        ensureNoComparator();
        box.getStore().runInReadTx(new Runnable() {
            @Override
            public void run() {
                LazyList<T> lazyList = new LazyList<>(box, findIds(), false);
                int size = lazyList.size();
                for (int i = 0; i < size; i++) {
                    T entity = lazyList.get(i);
                    if (entity == null) {
                        throw new IllegalStateException("Internal error: data object was null");
                    }
                    if (filter != null) {
                        if (!filter.keep(entity)) {
                            continue;
                        }
                    }
                    if (eagerRelations != null) {
                        resolveEagerRelationForNonNullEagerRelations(entity, i);
                    }
                    try {
                        consumer.accept(entity);
                    } catch (BreakForEach breakForEach) {
                        break;
                    }
                }
            }
        });
    }

    /**
     * Find all Objects matching the query without actually loading the Objects. See @{@link LazyList} for details.
     */
    @Nonnull
    public LazyList<T> findLazyCached() {
        ensureNoFilterNoComparator();
        return new LazyList<>(box, findIds(), true);
    }

    void resolveEagerRelations(List entities) {
        if (eagerRelations != null) {
            int entityIndex = 0;
            for (Object entity : entities) {
                resolveEagerRelationForNonNullEagerRelations(entity, entityIndex);
                entityIndex++;
            }
        }
    }

    /** Note: no null check on eagerRelations! */
    void resolveEagerRelationForNonNullEagerRelations(@Nonnull Object entity, int entityIndex) {
        for (EagerRelation eagerRelation : eagerRelations) {
            if (eagerRelation.limit == 0 || entityIndex < eagerRelation.limit) {
                resolveEagerRelation(entity, eagerRelation);
            }
        }
    }

    void resolveEagerRelation(@Nullable Object entity) {
        if (eagerRelations != null && entity != null) {
            for (EagerRelation eagerRelation : eagerRelations) {
                resolveEagerRelation(entity, eagerRelation);
            }
        }
    }

    void resolveEagerRelation(@Nonnull Object entity, EagerRelation eagerRelation) {
        if (eagerRelations != null) {
            RelationInfo relationInfo = eagerRelation.relationInfo;
            if (relationInfo.toOneGetter != null) {
                ToOne toOne = relationInfo.toOneGetter.getToOne(entity);
                if (toOne != null) {
                    toOne.getTarget();
                }
            } else {
                if (relationInfo.toManyGetter == null) {
                    throw new IllegalStateException("Relation info without relation getter: " + relationInfo);
                }
                List toMany = relationInfo.toManyGetter.getToMany(entity);
                if (toMany != null) {
                    toMany.size();
                }
            }
        }
    }

    /** Returns the count of Objects matching the query. */
    public long count() {
        return box.internalCallWithReaderHandle(new CallWithHandle<Long>() {
            @Override
            public Long call(long cursorHandle) {
                return nativeCount(handle, cursorHandle);
            }
        });
    }

    /** Sums up all values for the given property over all Objects matching the query. */
    public long sum(final Property property) {
        return box.internalCallWithReaderHandle(new CallWithHandle<Long>() {
            @Override
            public Long call(long cursorHandle) {
                return nativeSum(handle, cursorHandle, property.getId());
            }
        });
    }

    /** Sums up all values for the given property over all Objects matching the query. */
    public double sumDouble(final Property property) {
        return box.internalCallWithReaderHandle(new CallWithHandle<Double>() {
            @Override
            public Double call(long cursorHandle) {
                return nativeSumDouble(handle, cursorHandle, property.getId());
            }
        });
    }

    /** Finds the maximum value for the given property over all Objects matching the query. */
    public long max(final Property property) {
        return box.internalCallWithReaderHandle(new CallWithHandle<Long>() {
            @Override
            public Long call(long cursorHandle) {
                return nativeMax(handle, cursorHandle, property.getId());
            }
        });
    }

    /** Finds the maximum value for the given property over all Objects matching the query. */
    public double maxDouble(final Property property) {
        return box.internalCallWithReaderHandle(new CallWithHandle<Double>() {
            @Override
            public Double call(long cursorHandle) {
                return nativeMaxDouble(handle, cursorHandle, property.getId());
            }
        });
    }

    /** Finds the minimum value for the given property over all Objects matching the query. */
    public long min(final Property property) {
        return box.internalCallWithReaderHandle(new CallWithHandle<Long>() {
            @Override
            public Long call(long cursorHandle) {
                return nativeMin(handle, cursorHandle, property.getId());
            }
        });
    }

    /** Finds the minimum value for the given property over all Objects matching the query. */
    public double minDouble(final Property property) {
        return box.internalCallWithReaderHandle(new CallWithHandle<Double>() {
            @Override
            public Double call(long cursorHandle) {
                return nativeMinDouble(handle, cursorHandle, property.getId());
            }
        });
    }

    /** Calculates the average of all values for the given property over all Objects matching the query. */
    public double avg(final Property property) {
        return box.internalCallWithReaderHandle(new CallWithHandle<Double>() {
            @Override
            public Double call(long cursorHandle) {
                return nativeAvg(handle, cursorHandle, property.getId());
            }
        });
    }


    /**
     * Sets a parameter previously given to the {@link QueryBuilder} to a new value.
     */
    public Query<T> setParameter(Property property, String value) {
        nativeSetParameter(handle, property.getId(), null, value);
        return this;
    }

    /**
     * Sets a parameter previously given to the {@link QueryBuilder} to a new value.
     */
    public Query<T> setParameter(Property property, long value) {
        nativeSetParameter(handle, property.getId(), null, value);
        return this;
    }

    /**
     * Sets a parameter previously given to the {@link QueryBuilder} to a new value.
     */
    public Query<T> setParameter(Property property, double value) {
        nativeSetParameter(handle, property.getId(), null, value);
        return this;
    }

    /**
     * Sets a parameter previously given to the {@link QueryBuilder} to a new value.
     *
     * @throws NullPointerException if given date is null
     */
    public Query<T> setParameter(Property property, Date value) {
        return setParameter(property, value.getTime());
    }

    /**
     * Sets a parameter previously given to the {@link QueryBuilder} to a new value.
     */
    public Query<T> setParameter(Property property, boolean value) {
        return setParameter(property, value ? 1 : 0);
    }

    /**
     * Sets a parameter previously given to the {@link QueryBuilder} to new values.
     */
    public Query<T> setParameters(Property property, long value1, long value2) {
        nativeSetParameters(handle, property.getId(), null, value1, value2);
        return this;
    }

    /**
     * Sets a parameter previously given to the {@link QueryBuilder} to new values.
     */
    public Query<T> setParameters(Property property, double value1, double value2) {
        nativeSetParameters(handle, property.getId(), null, value1, value2);
        return this;
    }

    /**
     * Removes (deletes) all Objects matching the query
     *
     * @return count of removed Objects
     */
    public long remove() {
        return box.internalCallWithWriterHandle(new CallWithHandle<Long>() {
            @Override
            public Long call(long cursorHandle) {
                return nativeRemove(handle, cursorHandle);
            }
        });
    }

    /**
     * A {@link io.objectbox.reactive.DataObserver} can be subscribed to data changes using the returned builder.
     * The observer is supplied via {@link SubscriptionBuilder#observer(DataObserver)} and will be notified once
     * the query results have (potentially) changed.
     * <p>
     * With subscribing, the observer will immediately get current query results.
     * The query is run for the subscribing observer.
     * <p>
     * Threading notes:
     * Query observers are notified from a thread pooled. Observers may be notified in parallel.
     * The notification order is the same as the subscription order, although this may not always be guaranteed in
     * the future.
     * <p>
     * Stale observers: you must hold on to the Query or {@link io.objectbox.reactive.DataSubscription} objects to keep
     * your {@link DataObserver}s active. If this Query is not referenced anymore
     * (along with its {@link io.objectbox.reactive.DataSubscription}s, which hold a reference to the Query internally),
     * it may be GCed and observers may become stale (won't receive anymore data).
     */
    public SubscriptionBuilder<List<T>> subscribe() {
        return new SubscriptionBuilder<>(publisher, null, box.getStore().internalThreadPool());
    }

    /**
     * Convenience for {@link #subscribe()} with a subsequent call to
     * {@link SubscriptionBuilder#dataSubscriptionList(DataSubscriptionList)}.
     *
     * @param dataSubscriptionList the resulting {@link io.objectbox.reactive.DataSubscription} will be added to it
     */
    public SubscriptionBuilder<List<T>> subscribe(DataSubscriptionList dataSubscriptionList) {
        SubscriptionBuilder<List<T>> subscriptionBuilder = subscribe();
        subscriptionBuilder.dataSubscriptionList(dataSubscriptionList);
        return subscriptionBuilder;
    }

    /**
     * Publishes the current data to all subscribed @{@link DataObserver}s.
     * This is useful triggering observers when new parameters have been set.
     * Note, that setParameter methods will NOT be propagated to observers.
     */
    public void publish() {
        publisher.publish();
    }

}
