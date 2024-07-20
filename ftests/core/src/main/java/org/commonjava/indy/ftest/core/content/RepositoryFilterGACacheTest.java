/**
 * Copyright (C) 2011-2023 Red Hat, Inc. (https://github.com/Commonjava/indy)
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
package org.commonjava.indy.ftest.core.content;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.core.content.group.GroupRepositoryFilterManager;
import org.commonjava.indy.ftest.core.AbstractContentManagementTest;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.pathmapped.cache.PathMappedMavenGACache;
import org.commonjava.indy.pathmapped.inject.PathMappedGroupRepositoryFilter;
import org.commonjava.indy.pathmapped.inject.PathMappedMavenGACacheGroupRepositoryFilter;
import org.commonjava.test.http.junit4.expect.ExpectationServerWrapper;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.inject.spi.CDI;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Given:
 *   - ga-cache.store.pattern=build-\d
 *
 * Given:
 *   Remote repo R
 *   Remote repo R contains GA_0 (version 1.0)
 *
 *   Hosted repo npc-builds, contains GA_1 (version 1.0) and GA_2 (version 1.0)
 *
 *   Hosted repo build-1,2,3,4
 *   Hosted repo build-1,2 contains GA_1 (version 2.0-bluehat-1, 2.0-bluehat-2)
 *   Hosted repo build-3,4 contains GA_2 (version 2.0-bluehat-1, 2.0-bluehat-2)
 *
 *   Group G contains build-1,2,3,4, R, npc-builds
 *
 * When:
 *   - Get pom path (GA_0 + version 1.0) from group G
 *
 * Then:
 *   - Filter the hosted build-1,2,3,4 and get the content from remote R
 *
 * When:
 *   - Get pom path (GA_1 + version 2.0-bluehat-1) from group G
 *
 * Then:
 *   - Filter the hosted build-1,2 and get the content from build-1
 *
 * When:
 *   - Get pom path (GA_2 + version 2.0-bluehat-1) from group G
 *
 * Then:
 *   - Filter the hosted build-3,4 and get the content from build-3
 *
 * When:
 *   - Get pom path (GA_1 + version 1.0) from group G
 *
 * Then:
 *   - Filter the hosted build-1,2 and get the content from npc-builds
 *
 * When:
 *   - Get metadata GA_0 from group G
 *
 * Then:
 *   - Filter the hosted build-1,2,3,4 and get from R
 *
 * When:
 *   - Get metadata GA_1 from group G
 *
 * Then:
 *   - Filter the hosted build-1,2, get merged content from build-1,2 and pnc-builds
 *
 * When:
 *   - Get metadata GA_2 from group G
 *
 * Then:
 *   - Filter the hosted build-3,4, get merged content from build-3,4 and pnc-builds
 *
 *
 * ########### NOTE ############
 *
 * Even if the GA cache filter does not work properly, the following filters and default retrieval logic will still
 * return the right file. We can only check the log to see whether the GA cache filter works. We also need to
 * make sure it works before the default path-mapped filter.
 */
public class RepositoryFilterGACacheTest
                extends AbstractContentManagementTest
{

    private final String GROUP_ID = "org/commonjava";

    private final String A_0 = "partyline";

    private final String A_1 = "path-mapped";

    private final String A_2 = "jhttpc";

    private final String V1 = "1.0";

    private final String V2_1 = "2.0-bluehat-1";

    private final String V2_2 = "2.0-bluehat-2";

    @Rule
    public ExpectationServerWrapper server = new ExpectationServerWrapper();

    /* @formatter:off */
    private static final String POM_TEMPLATE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<project>\n" +
        "  <modelVersion>4.0.0</modelVersion>\n" +
        "  <groupId>org/commonjava</groupId>\n" +
        "  <artifactId>%artifact%</artifactId>\n" +
        "  <version>%version%</version>\n" +
        "</project>\n";

    private static final String METADATA_TEMPLATE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<metadata>\n" +
        "  <groupId>org/commonjava</groupId>\n" +
        "  <artifactId>%artifact%</artifactId>\n" +
        "  <versioning>\n" +
        "    <latest>%version%</latest>\n" +
        "    <release>%version%</release>\n" +
        "    <versions>\n" +
        "      <version>%version%</version>\n" +
        "    </versions>\n" +
        "  </versioning>\n" +
        "</metadata>\n";
    /* @formatter:on */


    @Test
    public void run() throws Exception
    {
        BufferedLogAppender bufferedLogAppender = prepareTestAppender();

        final String remoteR = "R";

        final String build_1 = "build-1";
        final String build_2 = "build-2";
        final String build_3 = "build-3";
        final String build_4 = "build-4";

        final String npc_builds = "npc_builds";

        final String groupG = "G";

        RemoteRepository remote = prepareRemote( remoteR, A_0, V1 );

        HostedRepository hosted_1 = prepareHosted( build_1, A_1, V2_1 );
        HostedRepository hosted_2 = prepareHosted( build_2, A_1, V2_2 );
        HostedRepository hosted_3 = prepareHosted( build_3, A_2, V2_1 );
        HostedRepository hosted_4 = prepareHosted( build_4, A_2, V2_2 );

        HostedRepository hosted_npc_builds = prepareHosted( npc_builds, A_1, V1 );
        hosted_npc_builds = prepareHosted( npc_builds, A_2, V1 );

        List<StoreKey> storeKeys = new ArrayList<>();
        storeKeys.add( hosted_1.getKey() );
        storeKeys.add( hosted_2.getKey() );
        storeKeys.add( hosted_3.getKey() );
        storeKeys.add( hosted_4.getKey() );
        storeKeys.add( hosted_npc_builds.getKey() );
        storeKeys.add( remote.getKey() );

        // add confusing repos, hope it is not confused
        for ( int i = 100; i < 200; i++ )
        {
            HostedRepository h = client.stores()
                                       .create( new HostedRepository( MAVEN_PKG_KEY, "build-" + i ), "Add hosted-" + i,
                                                HostedRepository.class );
            storeKeys.add( h.getKey() );
        }

        // create group G
        StoreKey[] keyArray = new StoreKey[storeKeys.size()];
        Group g = client.stores()
                        .create( new Group( MAVEN_PKG_KEY, groupG, storeKeys.toArray( keyArray ) ), "Add group",
                                 Group.class );

        System.out.printf( "\n\nGroup constituents are:\n  %s\n\n", StringUtils.join( g.getConstituents(), "\n  " ) );

        // fill the GA cache (because when test fixture starts up the repos are not created yet)
        CDI.current().select( PathMappedMavenGACache.class).get().fill();

        // get pom
        String pomPath = getPomPath( A_0, V1 );
        try (InputStream stream = client.content().get( g.getKey(), pomPath ))
        {
            String str = IOUtils.toString( stream );
            System.out.println( ">>>> " + pomPath + "\n" + str );
            assertThat( str, containsString( "<artifactId>" + A_0 + "</artifactId>" ) );
            assertThat( str, containsString( "<version>" + V1 + "</version>" ) );
        }
        checkLogMessage( pomPath, bufferedLogAppender.getMessages().toString(),
                         "[maven:hosted:npc_builds, maven:remote:R]" );

        pomPath = getPomPath( A_1, V2_1 );
        try (InputStream stream = client.content().get( g.getKey(), pomPath ))
        {
            String str = IOUtils.toString( stream );
            System.out.println( ">>>> " + pomPath + "\n" + str );
            assertThat( str, containsString( "<artifactId>" + A_1 + "</artifactId>" ) );
            assertThat( str, containsString( "<version>" + V2_1 + "</version>" ) );
        }
        checkLogMessage( pomPath, bufferedLogAppender.getMessages().toString(),
                         "[maven:hosted:build-1, maven:hosted:build-2, maven:hosted:npc_builds, maven:remote:R]" );

        pomPath = getPomPath( A_2, V2_1 );
        try (InputStream stream = client.content().get( g.getKey(), pomPath ))
        {
            String str = IOUtils.toString( stream );
            System.out.println( ">>>> " + pomPath + "\n" + str );
            assertThat( str, containsString( "<artifactId>" + A_2 + "</artifactId>" ) );
            assertThat( str, containsString( "<version>" + V2_1 + "</version>" ) );
        }
        checkLogMessage( pomPath, bufferedLogAppender.getMessages().toString(),
                         "[maven:hosted:build-3, maven:hosted:build-4, maven:hosted:npc_builds, maven:remote:R]" );

        pomPath = getPomPath( A_1, V1 );
        try (InputStream stream = client.content().get( g.getKey(), pomPath ))
        {
            String str = IOUtils.toString( stream );
            System.out.println( ">>>> " + pomPath + "\n" + str );
            assertThat( str, containsString( "<artifactId>" + A_1 + "</artifactId>" ) );
            assertThat( str, containsString( "<version>" + V1 + "</version>" ) );
        }
        checkLogMessage( pomPath, bufferedLogAppender.getMessages().toString(),
                         "[maven:hosted:build-1, maven:hosted:build-2, maven:hosted:npc_builds, maven:remote:R]" );

        // get metadata
        String metadataPath = getMetadataPath( A_0 );
        try (InputStream stream = client.content().get( g.getKey(), metadataPath ))
        {
            String str = IOUtils.toString( stream );
            System.out.println( ">>>> " + metadataPath + "\n" + str );
            assertThat( str, containsString( "<artifactId>" + A_0 + "</artifactId>" ) );
            assertThat( str, containsString( "<version>" + V1 + "</version>" ) );
        }
        checkLogMessage( metadataPath, bufferedLogAppender.getMessages().toString(),
                         "[maven:hosted:npc_builds, maven:remote:R]" );

        metadataPath = getMetadataPath( A_1 );
        try (InputStream stream = client.content().get( g.getKey(), metadataPath ))
        {
            String str = IOUtils.toString( stream );
            System.out.println( ">>>> " + metadataPath + "\n" + str );
            assertThat( str, containsString( "<artifactId>" + A_1 + "</artifactId>" ) );
            assertThat( str, containsString( "<version>" + V1 + "</version>" ) );
            assertThat( str, containsString( "<version>" + V2_1 + "</version>" ) );
            assertThat( str, containsString( "<version>" + V2_2 + "</version>" ) );
        }
        checkLogMessage( metadataPath, bufferedLogAppender.getMessages().toString(),
                         "[maven:hosted:build-1, maven:hosted:build-2, maven:hosted:npc_builds, maven:remote:R]" );

        metadataPath = getMetadataPath( A_2 );
        try (InputStream stream = client.content().get( g.getKey(), metadataPath ))
        {
            String str = IOUtils.toString( stream );
            System.out.println( ">>>> " + metadataPath + "\n" + str );
            assertThat( str, containsString( "<artifactId>" + A_2 + "</artifactId>" ) );
            assertThat( str, containsString( "<version>" + V1 + "</version>" ) );
            assertThat( str, containsString( "<version>" + V2_1 + "</version>" ) );
            assertThat( str, containsString( "<version>" + V2_2 + "</version>" ) );
        }
        checkLogMessage( metadataPath, bufferedLogAppender.getMessages().toString(),
                         "[maven:hosted:build-3, maven:hosted:build-4, maven:hosted:npc_builds, maven:remote:R]" );

        //
        String messages = bufferedLogAppender.getMessages().toString();
        System.out.println(">>>>\n{\n" + messages + "}\n\n");
    }

    /**
     * Check 1. GA cache filter result match expected; 2. it works before the default path-mapped filter
     */
    private void checkLogMessage( String path, String messages, String expected )
    {
        int gaFilterIndex = 0, defaultFilterIndex = 0;
        String[] lines = messages.split( "\n" );
        for ( int i = 0; i < lines.length; i++ )
        {
            String line = lines[i];
            if ( line.contains( path ) )
            {
                if ( line.contains( PathMappedMavenGACacheGroupRepositoryFilter.class.getSimpleName() ) )
                {
                    assertTrue( line.contains( expected ) );
                    gaFilterIndex = i;
                }
                else if ( line.contains( PathMappedGroupRepositoryFilter.class.getSimpleName() ) )
                {
                    defaultFilterIndex = i;
                }
            }
        }
        assertTrue( gaFilterIndex < defaultFilterIndex );
    }

    class BufferedLogAppender
                    extends AppenderBase<ILoggingEvent>
    {
        private StringBuilder messages = new StringBuilder(  );
        private String filter;

        public BufferedLogAppender( String filter )
        {
            this.filter = filter;
        }

        @Override
        protected void append( ILoggingEvent event )
        {
            String message = event.getFormattedMessage();
            if ( message.contains( filter ) )
            {
                messages.append( "[" + event.getThreadName() + "] " + message + "\n" );
            }
        }

        public StringBuilder getMessages()
        {
            return messages;
        }
    }

    private BufferedLogAppender prepareTestAppender()
    {
        BufferedLogAppender ret = null;
        Logger filterManagerLogger = LoggerFactory.getLogger( GroupRepositoryFilterManager.class );
        if ( filterManagerLogger instanceof ch.qos.logback.classic.Logger )
        {
            ch.qos.logback.classic.Logger logbackLogger = (ch.qos.logback.classic.Logger) filterManagerLogger;
            ret = new BufferedLogAppender( "Filter processed" );
            ret.start();
            logbackLogger.addAppender( ret );
        }
        return ret;
    }

    private String getPomPath( String A, String V )
    {
        return Paths.get( GROUP_ID, A, V, A + "-" + V + ".pom" ).toString();
    }

    private String getMetadataPath( String A )
    {
        return Paths.get( GROUP_ID, A, "maven-metadata.xml" ).toString();
    }

    private RemoteRepository prepareRemote( String name, String A, String V ) throws Exception
    {
        String pomPath = getPomPath( A, V );
        String metadataPath = getMetadataPath( A );

        server.expect( server.formatUrl( name, pomPath ), 200,
                       POM_TEMPLATE.replaceAll( "%artifact%", A ).replaceAll( "%version%", V ) );
        server.expect( server.formatUrl( name, metadataPath ), 200,
                       METADATA_TEMPLATE.replaceAll( "%artifact%", A ).replaceAll( "%version%", V ) );

        RemoteRepository remote = new RemoteRepository( MAVEN_PKG_KEY, name, server.formatUrl( name ) );
        if ( !client.stores().exists( remote.getKey() ) )
        {
            remote = client.stores().create( remote, "Add " + name, RemoteRepository.class );
        }
        return remote;
    }

    private HostedRepository prepareHosted( String name, String A, String V ) throws IndyClientException
    {
        HostedRepository hosted = new HostedRepository( MAVEN_PKG_KEY, name );
        if ( !client.stores().exists( hosted.getKey() ) )
        {
            hosted = client.stores().create( hosted, "Add " + name, HostedRepository.class );
        }

        String pomPath = getPomPath( A, V );
        String metadataPath = getMetadataPath( A );

        client.content()
              .store( hosted.getKey(), pomPath, new ByteArrayInputStream(
                              POM_TEMPLATE.replaceAll( "%artifact%", A ).replaceAll( "%version%", V ).getBytes() ) );
        client.content()
              .store( hosted.getKey(), metadataPath, new ByteArrayInputStream(
                              METADATA_TEMPLATE.replaceAll( "%artifact%", A )
                                               .replaceAll( "%version%", V )
                                               .getBytes() ) );
        return hosted;
    }

    @Override
    protected boolean createStandardTestStructures()
    {
        return false;
    }
}
