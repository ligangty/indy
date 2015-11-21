/**
 * Copyright (C) 2011 Red Hat, Inc. (jdcasey@commonjava.org)
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
package org.commonjava.aprox.folo.data;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import org.commonjava.aprox.folo.conf.FoloConfig;
import org.commonjava.aprox.folo.model.TrackedContentRecord;
import org.commonjava.aprox.folo.model.TrackingKey;
import org.commonjava.aprox.model.core.io.AproxObjectMapper;
import org.commonjava.aprox.subsys.datafile.DataFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class FoloRecordCache
    extends CacheLoader<TrackingKey, TrackedContentRecord>
    implements RemovalListener<TrackingKey, TrackedContentRecord>
{

    private static final String JSON_TYPE = "json";

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private AproxObjectMapper objectMapper;

    @Inject
    private FoloConfig config;

    @Inject
    private FoloFiler filer;

    protected Cache<TrackingKey, TrackedContentRecord> recordCache;

    protected FoloRecordCache()
    {
    }

    public FoloRecordCache( final FoloFiler filer, final AproxObjectMapper objectMapper,
                            final FoloConfig config )
    {
        this.filer = filer;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    @PostConstruct
    public void buildCache()
    {
        final CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder();

        builder.expireAfterAccess( config.getCacheTimeoutSeconds(), TimeUnit.SECONDS )
               .removalListener( this );

        recordCache = builder.build( this );
    }

    @Override
    public void onRemoval( final RemovalNotification<TrackingKey, TrackedContentRecord> notification )
    {
        final TrackingKey key = notification.getKey();
        if ( key == null )
        {
            logger.info( "Nothing to persist. Skipping." );
            return;
        }

        write( notification.getValue() );
    }

    protected void write( final TrackedContentRecord record )
    {
        final TrackingKey key = record.getKey();

        final File file = filer.getRecordFile( key ).getDetachedFile();
        logger.info( "Writing {} to: {}", key, file );
        try
        {
            file.getParentFile()
                .mkdirs();
            objectMapper.writeValue( file, record );
        }
        catch ( final IOException e )
        {
            logger.error( "Failed to persist folo log of artifact usage via: " + key, e );
        }
    }

    @Override
    public TrackedContentRecord load( final TrackingKey key )
        throws Exception
    {
        final DataFile file = filer.getRecordFile( key );
        if ( !file.exists() )
        {
            logger.info( "Creating new record for: {}", key );
            return new TrackedContentRecord( key );
        }

        logger.info( "Loading: {} from: {}", key, file );
        try
        {
            return objectMapper.readValue( file.getDetachedFile(), TrackedContentRecord.class );
        }
        catch ( final IOException e )
        {
            logger.error( "Failed to read folo tracked record: " + key, e );
            throw new IllegalStateException( "Requested artimon tracked record: " + key
                + " is corrupt, and cannot be read.", e );
        }
    }

    public void delete( final TrackingKey key )
    {
        recordCache.invalidate( key );
        filer.deleteFiles( key );
    }

    public Callable<? extends TrackedContentRecord> newCallable( final TrackingKey trackedStore )
    {
        return new LoaderCall( this, trackedStore );
    }

    private static final class LoaderCall
        implements Callable<TrackedContentRecord>
    {
        private final FoloRecordCache persister;

        private final TrackingKey key;

        public LoaderCall( final FoloRecordCache persister, final TrackingKey key )
        {
            this.persister = persister;
            this.key = key;
        }

        @Override
        public TrackedContentRecord call()
            throws Exception
        {
            return persister.load( key );
        }
    }

    public boolean hasRecord( final TrackingKey key )
    {
        logger.info( "Looking for tracking record: {}.\nCache record: {}\nRecord file: {}", key,
                     recordCache.getIfPresent( key ), filer.getRecordFile( key ) );

        return recordCache.getIfPresent( key ) != null || filer.getRecordFile( key ).exists();
    }

    public TrackedContentRecord get( final TrackingKey key )
        throws FoloContentException
    {
        try
        {
            return recordCache.get( key, newCallable( key ) );
        }
        catch ( final ExecutionException e )
        {
            throw new FoloContentException( "Failed to load tracking record for: %s. Reason: %s", e, key,
                                            e.getMessage() );
        }
    }

}