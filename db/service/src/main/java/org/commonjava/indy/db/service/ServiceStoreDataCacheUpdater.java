/**
 * Copyright (C) 2022 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.db.service;

import org.commonjava.cdi.util.weft.ExecutorConfig;
import org.commonjava.cdi.util.weft.WeftExecutorService;
import org.commonjava.cdi.util.weft.WeftManaged;
import org.commonjava.indy.change.event.ArtifactStoreDeletePostEvent;
import org.commonjava.indy.change.event.ArtifactStorePostUpdateEvent;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.subsys.infinispan.BasicCacheHandle;
import org.commonjava.indy.subsys.infinispan.CacheProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static org.commonjava.indy.change.event.ArtifactStoreUpdateType.ADD;

/**
 * This class will listen to all StorePostUpdateEvent and StorePostDeleteEvent to update the
 * Cache of Store Data and Store Query Data which is used in ServiceStoreDataManager and ServiceStoreQuery
 *
 */
@ApplicationScoped
@SuppressWarnings( "unused" )
public class ServiceStoreDataCacheUpdater
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private CacheProducer cacheProducer;

    @Inject
    @WeftManaged
    @ExecutorConfig( named = "service-data-cache-update-executor", threads = 2 )
    private WeftExecutorService cacheUpdateExecutor;

    public void onStoreUpdate( @Observes ArtifactStorePostUpdateEvent updateEvent )
    {
        logger.info( "Start cache updater for store post update event: {}", updateEvent );
        final BasicCacheHandle<StoreKey, ArtifactStore> storeCache =
                cacheProducer.getBasicCache( ServiceStoreDataManager.ARTIFACT_STORE );
        final BasicCacheHandle<Object, Collection<ArtifactStore>> queryCache =
                cacheProducer.getBasicCache( ServiceStoreQuery.ARTIFACT_STORE_QUERY );
        cacheUpdateExecutor.execute( () -> {
            if ( updateEvent.getType().equals( ADD ) )
            {
                for ( ArtifactStore newStore : updateEvent.getChanges() )
                {
                    logger.info( "Fresh the store cache on add event, newStore:{}", newStore );
                    storeCache.put( newStore.getKey(), newStore );
                    obsoleteQueryCache( newStore.getKey() );
                }
            }
            else
            {
                for ( Map.Entry<ArtifactStore, ArtifactStore> storeEntry : updateEvent.getChangeMap().entrySet() )
                {

                    final ArtifactStore newStore = storeEntry.getKey();
                    final ArtifactStore originalStore = storeEntry.getValue();
                    if ( storeCache.get( originalStore.getKey() ) != null )
                    {
                        logger.info( "Fresh the store cache on update event, originalStore: {}, newStore:{}",
                                     originalStore, newStore );
                        storeCache.remove( originalStore.getKey() );
                        storeCache.put( newStore.getKey(), newStore );
                    }

                    obsoleteQueryCache( newStore.getKey() );
                }
            }
        } );
    }

    public void onStoreDelete( @Observes ArtifactStoreDeletePostEvent deleteEvent )
    {
        logger.info( "Start cache updater for store delete post event: {}", deleteEvent );
        final BasicCacheHandle<StoreKey, ArtifactStore> storeCache =
                cacheProducer.getBasicCache( ServiceStoreDataManager.ARTIFACT_STORE );
        final BasicCacheHandle<Object, Collection<ArtifactStore>> queryCache =
                cacheProducer.getBasicCache( ServiceStoreQuery.ARTIFACT_STORE_QUERY );
        cacheUpdateExecutor.execute( () -> {
            for ( ArtifactStore deleted : deleteEvent.getStores() )
            {
                storeCache.execute( ( cache ) -> {
                    logger.info( "Fresh the store cache on delete event, deleted: {}", deleted );
                    cache.remove( deleted.getKey() );
                    cache.values()
                         .stream()
                         .filter( ( store ) -> store.getType() == StoreType.group )
                         .forEach( ( store ) -> ( (Group) store ).getConstituents().remove( deleted.getKey() ) );
                    return null;
                } );

                obsoleteQueryCache( deleted.getKey() );
            }
        } );

    }

    /**
     * For ServiceStoreQuery cache, we need to clear the related entry if any store event happened and
     * let the query refresh from remote repository service
     *
     * @param storeKey
     */
    private void obsoleteQueryCache( StoreKey storeKey )
    {
        final BasicCacheHandle<Object, Collection<ArtifactStore>> queryCache =
                cacheProducer.getBasicCache( ServiceStoreQuery.ARTIFACT_STORE_QUERY );
        queryCache.execute( ( cache ) -> {
            for ( Map.Entry<Object, Collection<ArtifactStore>> entry : cache.entrySet() )
            {
                Object key = entry.getKey();
                // This is for getGroupsAffectedBy query cache
                if ( key instanceof Set && ( (Set) key ).contains( storeKey ) )
                {
                    logger.info( "Fresh the store query cache, removed: {}", storeKey );
                    cache.remove( key );
                }
                // This is for getOrderedConcreteStoresInGroup query cache
                if ( storeKey.getType() == StoreType.group && key instanceof String && ( (String) key ).indexOf(
                        String.format( "%s:%s", storeKey.getPackageType(), storeKey.getName() ) ) > 0 )
                {
                    logger.info( "Fresh the concrete stores query cache, removed: {}", storeKey );
                    cache.remove( key );
                }

            }
            return null;
        } );
    }
}