/**
 * Copyright (C) 2011-2023 Red Hat, Inc. (https://github.com/Commonjava/indy)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 *         http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.ftest.core;

import com.datastax.driver.core.Session;
import com.fasterxml.jackson.databind.Module;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.commonjava.indy.action.IndyLifecycleException;
import org.commonjava.indy.client.core.Indy;
import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.client.core.IndyClientModule;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.subsys.cassandra.CassandraClient;
import org.commonjava.indy.test.fixture.core.CoreServerFixture;
import org.commonjava.maven.galley.spi.cache.CacheProvider;
import org.commonjava.propulsor.boot.BootException;
import org.commonjava.propulsor.boot.BootStatus;
import org.commonjava.test.http.junit4.expect.ExpectationServerWrapper;
import org.commonjava.util.jhttpc.auth.MemoryPasswordManager;
import org.commonjava.util.jhttpc.model.SiteConfig;
import org.commonjava.util.jhttpc.model.SiteConfigBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;
import org.junit.rules.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.inject.spi.CDI;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.commonjava.indy.pkg.PackageTypeConstants.PKG_TYPE_MAVEN;
import static org.junit.Assert.fail;

@SuppressWarnings( "ResultOfMethodCallIgnored" )
public abstract class AbstractIndyFunctionalTest
{
    private static final int NAME_LEN = 8;

    private static final String NAME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_";

    public static final long DEFAULT_TEST_TIMEOUT = 120;

    public static final String TIMEOUT_ENV_FACTOR_SYSPROP = "testEnvTimeoutMultiplier";

    protected Indy client;

    protected CoreServerFixture fixture;

    protected final Logger logger = LoggerFactory.getLogger( getClass() );

    protected static final int DEFAULT_MOCK_SERVER_PORT = 10028;

    protected static final String DEFAULT_MOCK_SERVICE_URL =
            String.format( "http://localhost:%s/api", DEFAULT_MOCK_SERVER_PORT );

    @Rule
    public ExpectationServerWrapper repoServiceServer =
            new ExpectationServerWrapper( "api", DEFAULT_MOCK_SERVER_PORT );

    protected RepoServiceUtil serviceUtil;

    @Rule
    public TestName name = new TestName();

    @Rule
    public Timeout timeout = Timeout.builder()
                                    .withLookingForStuckThread( true )
                                    .withTimeout( getTestTimeoutSeconds(), TimeUnit.SECONDS )
                                    .build();

    protected File etcDir;

    protected File dataDir;

    protected File storageDir;

    protected CacheProvider cacheProvider;

    @Before
    public void start()
            throws Throwable
    {
        try
        {
            final long start = System.currentTimeMillis();
            TimerTask task = new TimerTask()
            {
                @Override
                public void run()
                {
                    long time = System.currentTimeMillis();
                    System.out.printf( "\n\n\nDate: %s\nElapsed: %s\n\n\n", new Date( time ),
                                       Duration.between( Instant.ofEpochMilli( start ),
                                                         Instant.ofEpochMilli( time ) ) );
                }
            };

            new Timer().scheduleAtFixedRate( task, 0, 5000 );

            Thread.currentThread().setName( getClass().getSimpleName() + "." + name.getMethodName() );
            if ( isUseRepoService() )
            {
                logger.info( "The repo service is enabled, will start test repo service." );
                doServiceStart();
            }

            fixture = newServerFixture();
            fixture.start();

            if ( !fixture.isStarted() )
            {
                final BootStatus status = fixture.getBootStatus();
                throw new IllegalStateException( "server fixture failed to boot.", status.getError() );
            }

            client = createIndyClient();
            cacheProvider = CDI.current().select( CacheProvider.class ).get();
        }
        catch ( Throwable t )
        {
            logger.error( "Error initializing test", t );
            throw t;
        }
    }

    private void doServiceStart()
            throws Exception
    {
        serviceUtil = new RepoServiceUtil( repoServiceServer, DEFAULT_MOCK_SERVICE_URL );
        final HostedRepository defaultHosted = new HostedRepository( PKG_TYPE_MAVEN, "local-deployments" );
        serviceUtil.doCreateServiceRepo( defaultHosted.getKey(), serviceUtil.getStoreJson( defaultHosted ) );
        final RemoteRepository defaultRemote =
                new RemoteRepository( PKG_TYPE_MAVEN, "central", "https://repo.maven.apache.org/maven2" );
        serviceUtil.doCreateServiceRepo( defaultRemote.getKey(), serviceUtil.getStoreJson( defaultRemote ) );
        final Group defaultGroup = new Group( PKG_TYPE_MAVEN, "public" );
        serviceUtil.doCreateServiceRepo( defaultGroup.getKey(),
                                         serviceUtil.getJsonForGroupWithConstituents( defaultGroup.getKey(), defaultRemote.getKey(),
                                                              defaultHosted.getKey() ) );
        serviceUtil.doConcreteStoreQuery( defaultGroup.getKey(), defaultRemote, defaultHosted );
    }

    protected boolean isPathMappedStorageEnabled()
    {
        return true;
    }

    protected Indy createIndyClient()
            throws IndyClientException
    {
        SiteConfig config = new SiteConfigBuilder( "indy", fixture.getUrl() ).withRequestTimeoutSeconds( 60 ).build();
        Collection<IndyClientModule> modules = getAdditionalClientModules();

        return Indy.builder()
                   .setLocation( config )
                   .setPasswordManager( new MemoryPasswordManager() )
                   .setObjectMapper( new IndyObjectMapper( getAdditionalMapperModules() ) )
                   .setModules( modules.toArray( new IndyClientModule[0] ) )
                   .build();
    }

    protected float getTestEnvironmentTimeoutMultiplier()
    {
        return Float.parseFloat( System.getProperty( TIMEOUT_ENV_FACTOR_SYSPROP, "1" ) );
    }

    protected void waitForEventPropagation()
    {
        waitForEventPropagationWithMultiplier( getTestTimeoutMultiplier() );
    }

    protected void waitForEventPropagationWithMultiplier( int multiplier )
    {
        long ms = 1000L * multiplier;

        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.info( "Waiting {}ms for Indy server events to clear.", ms );
        // give events time to propagate
        try
        {
            Thread.sleep( ms );
        }
        catch ( InterruptedException e )
        {
            logger.error( e.getMessage(), e );
            fail( "Thread interrupted while waiting for server events to propagate." );
        }

        logger.info( "Resuming test" );
    }

    protected final long getTestTimeoutSeconds()
    {
        return (long) ( getTestTimeoutMultiplier() * getTestEnvironmentTimeoutMultiplier() * DEFAULT_TEST_TIMEOUT );
    }

    protected int getTestTimeoutMultiplier()
    {
        return 1;
    }

    @After
    public void stop()
            throws IndyLifecycleException
    {
        CassandraClient cassandraClient = CDI.current().select( CassandraClient.class ).get();
        dropKeyspace( "cache_", cassandraClient );
        dropKeyspace( "storage_", cassandraClient );
        dropKeyspace( "schedule_", cassandraClient );
        dropKeyspace( "store_", cassandraClient );

        cassandraClient.close();
        closeCacheProvider();
        closeQuietly( fixture );
        closeQuietly( client );
    }

    // TODO: this is a hack due to the "shutdown action not executed" issue. Once propulsor lifecycle shutdown is applied, this can be replaced.
    private void closeCacheProvider()
    {
        if ( cacheProvider != null )
        {
            cacheProvider.asAdminView().close();
        }
    }

    private void dropKeyspace( String prefix, CassandraClient cassandraClient )
    {
        String keyspace = getKeyspace( prefix );
        logger.debug( "Drop cassandra keyspace: {}", keyspace );
        Session session = cassandraClient.getSession( keyspace );
        if ( session != null )
        {
            try
            {
                session.execute( "DROP KEYSPACE IF EXISTS " + keyspace );
            }
            catch ( Exception ex )
            {
                logger.warn( "Failed to drop keyspace: {}, reason: {}", keyspace, ex.getMessage() );
            }
        }
    }

    protected void sleepAndRunFileGC( long milliseconds )
    {
        try
        {
            Thread.sleep( milliseconds );
        }
        catch ( InterruptedException e )
        {
            logger.error( e.getMessage(), e );
        }
        CacheProvider cacheProvider = CDI.current().select( CacheProvider.class ).get();
        cacheProvider.asAdminView().gc();
    }

    protected final CoreServerFixture newServerFixture()
            throws BootException, IOException
    {
        final CoreServerFixture fixture = new CoreServerFixture();

        logger.info( "Setting up configuration using indy.home == '{}'", fixture.getBootOptions().getHomeDir() );
        etcDir = new File( fixture.getBootOptions().getHomeDir(), "etc/indy" );
        dataDir = new File( fixture.getBootOptions().getHomeDir(), "var/lib/indy/data" );
        storageDir = new File( fixture.getBootOptions().getHomeDir(), "var/lib/indy/storage" );

        initBaseTestConfig( fixture );
        initTestConfig( fixture );
        initTestData( fixture );

        return fixture;
    }

    protected <T> T lookup( Class<T> component )
    {
        return CDI.current().select( component ).get();
    }

    protected void initTestConfig( CoreServerFixture fixture )
            throws IOException
    {
    }

    protected void initTestData( CoreServerFixture fixture )
            throws IOException
    {
    }

    protected void initBaseTestConfig( CoreServerFixture fixture )
            throws IOException
    {
        writeConfigFile( "conf.d/default.conf", "[default]\ncache.keyspace=" + getKeyspace( "cache_" )
                + "\naffected.groups.exclude=^build-\\d+"
                + "\nrepository.filter.enabled=true\nga-cache.store.pattern=^build-\\d+" );
        if ( isUseRepoService() )
        {
            writeConfigFile( "conf.d/default.conf", "\nstore.management.rest.enabled=false", true );
        }
        writeConfigFile( "conf.d/storage.conf",
                         "[storage-default]\n" + "storage.dir=" + fixture.getBootOptions().getHomeDir()
                                 + "/var/lib/indy/storage\n" + "storage.gc.graceperiodinhours=0\n"
                                 + "storage.gc.batchsize=0\n" + "storage.cassandra.keyspace=" + getKeyspace(
                                 "storage_" ) );

        writeConfigFile( "conf.d/cassandra.conf", "[cassandra]\nenabled=true" );
        writeConfigFile( "conf.d/store-manager.conf",
                         "[store-manager]\n" + "store.manager.keyspace=" + getKeyspace( "store_" )
                                 + "_stores\nstore.manager.replica=1" );

        writeConfigFile( "conf.d/scheduledb.conf", "[scheduledb]\nschedule.keyspace=" + getKeyspace( "schedule_" )
                + "_scheduler\nschedule.keyspace.replica=1\n"
                + "schedule.partition.range=3600000\nschedule.rate.period=3" );

        writeConfigFile( "conf.d/durable-state.conf",
                         "[durable-state]\n" + "folo.storage=infinispan\n" + "store.storage=standalone\n"
                                 + "schedule.storage=infinispan" );

        writeConfigFile( "conf.d/kafka.conf",
                         "[kafka]\n" + "enabled=true\n" + "kafka.bootstrap.servers=127.0.0.1:9092\n"
                                 + "kafka.topics=store-event\n" + "kafka.group=kstreams-group" );

        writeConfigFile( "conf.d/folo.conf", "[folo]\nfolo.cassandra=true" + "\nfolo.cassandra.keyspace=folo"
                + "\ntrack.group.content=True" );

        if ( isSchedulerEnabled() )
        {
            writeConfigFile( "conf.d/scheduledb.conf", readTestResource( "default-test-scheduledb.conf" ) );
            writeConfigFile( "conf.d/threadpools.conf", "[threadpools]\nenabled=false" );
            writeConfigFile( "conf.d/internal-features.conf",
                             "[_internal]\nstore.validation.enabled=false\nstore.auto.disable.reenable=true\n" );
            writeConfigFile( "conf.d/durable-state.conf", readTestResource( "default-durable-state.conf" ) );
        }
        else
        {
            writeConfigFile( "conf.d/scheduler.conf", "[scheduler]\nenabled=false" );
        }

        if ( isUseRepoService() )
        {
            logger.info( "The repo service is enabled, will use service based store management." );
            writeConfigFile( "conf.d/durable-state.conf",
                             "[durable-state]\n" + "folo.storage=infinispan\n" + "store.storage=service\n"
                                     + "schedule.storage=infinispan" );
            writeConfigFile( "conf.d/repo-service.conf", readTestResource( "default-test-repo-service.conf" ) );
        }
    }

    private String getKeyspace( String prefix )
    {
        String keyspace = prefix + getClass().getSimpleName();
        if ( keyspace.length() > 48 )
        {
            keyspace = keyspace.substring( 0, 48 ); // keyspace has to be less than 48 characters
        }
        return keyspace;
    }

    protected boolean isSchedulerEnabled()
    {
        return true;
    }

    protected boolean isUseRepoService()
    {
        return true;
    }

    protected String readTestResource( String resource )
            throws IOException
    {
        return IOUtils.toString( readTestResourceAsStream( resource ), Charset.defaultCharset() );
    }

    protected InputStream readTestResourceAsStream( String resource )
            throws IOException
    {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream( resource );
    }

    protected void writeConfigFile( String confPath, String contents )
            throws IOException
    {
        writeConfigFile( confPath, contents, false );
    }

    protected void writeConfigFile( String confPath, String contents, boolean append )
            throws IOException
    {
        File confFile = new File( etcDir, confPath );
        if ( !confFile.exists() || !append )
        {
            logger.info( "Writing configuration to: {}\n\n{}\n\n", confFile, contents );
            confFile.getParentFile().mkdirs();
            FileUtils.write( confFile, contents, Charset.defaultCharset(), false );
        }
        else
        {
            FileUtils.write( confFile, contents, Charset.defaultCharset(), true );
        }
    }

    protected void writeDataFile( String path, String contents )
            throws IOException
    {
        writeDataFile( path, contents, false );
    }

    protected void writeDataFile( String path, String contents, boolean append )
            throws IOException
    {
        File file = new File( dataDir, path );

        if ( !file.exists() || !append )
        {
            logger.info( "Writing data file to: {}\n\n{}\n\n", file, contents );
            file.getParentFile().mkdirs();
            FileUtils.write( file, contents, Charset.defaultCharset(), false );
        }
        else
        {
            FileUtils.write( file, contents, Charset.defaultCharset(), true );
        }
    }

    protected void copyToDataFile( String resourcePath, String path )
            throws IOException
    {
        File file = new File( dataDir, path );
        logger.info( "Writing data file to: {}, from: {}", file, resourcePath );
        file.getParentFile().mkdirs();
        FileUtils.copyInputStreamToFile(
                Thread.currentThread().getContextClassLoader().getResourceAsStream( resourcePath ), file );
    }

    protected void copyToConfigFile( String resourcePath, String path )
            throws IOException
    {
        File file = new File( etcDir, path );
        logger.info( "Writing data file to: {}, from: {}", file, resourcePath );
        file.getParentFile().mkdirs();
        FileUtils.copyInputStreamToFile(
                Thread.currentThread().getContextClassLoader().getResourceAsStream( resourcePath ), file );
    }

    protected Collection<Module> getAdditionalMapperModules()
    {
        return Collections.emptySet();
    }

    protected Collection<IndyClientModule> getAdditionalClientModules()
    {
        return Collections.emptySet();
    }

    protected String newName()
    {
        final SecureRandom rand = new SecureRandom();
        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < NAME_LEN; i++ )
        {
            sb.append( NAME_CHARS.charAt( ( Math.abs( rand.nextInt() ) % ( NAME_CHARS.length() - 1 ) ) ) );
        }

        return sb.toString();
    }

    protected String newUrl()
    {
        return String.format( "http://%s.com/", newName() );
    }

    protected TemporaryFolder getTemp()
    {
        return fixture.getTempFolder();
    }

    protected boolean isEmpty( String val )
    {
        return val == null || val.isEmpty();
    }

}
