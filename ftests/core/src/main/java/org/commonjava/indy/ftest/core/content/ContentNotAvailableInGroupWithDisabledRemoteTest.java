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

import org.apache.commons.io.IOUtils;
import org.commonjava.indy.ftest.core.AbstractIndyFunctionalTest;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.pkg.PackageTypeConstants;
import org.commonjava.test.http.junit4.expect.ExpectationServerWrapper;
import org.junit.Rule;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.Charset;

import static org.commonjava.indy.pkg.PackageTypeConstants.PKG_TYPE_MAVEN;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * <b>GIVEN:</b>
 * <ul>
 *     <li>A group contains a remote</li>
 *     <li>Content in the remote</li>
 *     <li>Content available through group via remote</li>
 * </ul>
 *
 * <br/>
 * <b>WHEN:</b>
 * <ul>
 *     <li>Remote set disabled</li>
 * </ul>
 *
 * <br/>
 * <b>THEN:</b>
 * <ul>
 *     <li>Content not available in group</li>
 * </ul>
 */
public class ContentNotAvailableInGroupWithDisabledRemoteTest
        extends AbstractIndyFunctionalTest
{
    @Rule
    public ExpectationServerWrapper server = new ExpectationServerWrapper();

    @Test
    public void contentAccess()
            throws Exception
    {
        final String remoteName = "r";
        final String groupName = "g";
        final String path = "org/foo/bar/foo-bar-1.txt";
        final String content = "This is a test";

        server.expect( server.formatUrl( remoteName, path ), 200, content );

        RemoteRepository r = new RemoteRepository( PKG_TYPE_MAVEN, remoteName, server.formatUrl( remoteName ) );
        Group g = new Group( PKG_TYPE_MAVEN, groupName, r.getKey() );
        if ( isUseRepoService() )
        {
            serviceUtil.doUpdateServiceRepo( r.getKey(), serviceUtil.getStoreJson( r ) );
            serviceUtil.doUpdateServiceRepo( g.getKey(), serviceUtil.getStoreJson( g ) );
            serviceUtil.doConcreteStoreQuery( g.getKey(), r );
        }
        else
        {
            r = client.stores().create( r, "adding remote", RemoteRepository.class );
            g = client.stores().create( g, "adding group", Group.class );
        }

        try (InputStream in = client.content().get( g.getKey(), path ))
        {
            assertThat( IOUtils.toString( in, Charset.defaultCharset() ), equalTo( content ) );
        }

        r.setDisabled( true );
        if ( isUseRepoService() )
        {
            serviceUtil.doUpdateServiceRepo( r.getKey(), serviceUtil.getStoreJson( r ) );
            serviceUtil.clearConcreteStoreQuery( g.getKey() );
        }
        else
        {
            client.stores().update( r, "adding remote" );
        }

        assertThat( client.content().exists( g.getKey(), path ), equalTo( false ) );
    }
}
