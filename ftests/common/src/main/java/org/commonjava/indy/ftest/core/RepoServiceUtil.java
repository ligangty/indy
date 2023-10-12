/**
 * Copyright (C) 2023 Red Hat, Inc.
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
package org.commonjava.indy.ftest.core;

import org.commonjava.indy.model.core.AbstractRepository;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.dto.StoreListingDTO;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.util.ApplicationStatus;
import org.commonjava.test.http.junit4.expect.ExpectationServerWrapper;

import javax.ws.rs.HttpMethod;
import java.util.List;

import static java.lang.String.format;
import static org.commonjava.indy.model.core.StoreType.group;
import static org.commonjava.indy.util.ApplicationStatus.CREATED;
import static org.commonjava.indy.util.ApplicationStatus.NOT_FOUND;
import static org.commonjava.indy.util.ApplicationStatus.OK;

public class RepoServiceUtil
{
    private final IndyObjectMapper mapper = new IndyObjectMapper( false );

    private final ExpectationServerWrapper repoServiceServer;

    private final String mockServiceURL;

    RepoServiceUtil( final ExpectationServerWrapper repoServiceServer, final String mockServiceURL )
    {
        this.repoServiceServer = repoServiceServer;
        this.mockServiceURL = mockServiceURL;
    }

    public void doCreateServiceRepo( final StoreKey key, final String storeBody )
            throws Exception
    {
        final String keyPath = key.toString().replaceAll( ":", "/" );
        final String storePath = format( "%s/admin/stores/%s", mockServiceURL, keyPath );
        repoServiceServer.expect( HttpMethod.POST, storePath, CREATED.code(), storeBody );
        repoServiceServer.expect( storePath, OK.code(), storeBody );
    }

    public void doUpdateServiceRepo( final StoreKey key, final String storeBody )
            throws Exception
    {
        final String keyPath = key.toString().replaceAll( ":", "/" );
        final String storePath = format( "%s/admin/stores/%s", mockServiceURL, keyPath );
        repoServiceServer.expect( HttpMethod.PUT, storePath, OK.code(), storeBody );
        repoServiceServer.expect( storePath, OK.code(), storeBody );
    }

    public void doGetServiceRepo( final StoreKey key, final String storeBody )
            throws Exception
    {
        final String keyPath = key.toString().replaceAll( ":", "/" );
        final String storePath = format( "%s/admin/stores/%s", mockServiceURL, keyPath );
        repoServiceServer.expect( storePath, OK.code(), storeBody );
    }

    public void doDeleteServiceRepo( final StoreKey key )
            throws Exception
    {
        final String keyPath = key.toString().replaceAll( ":", "/" );
        final String storePath = format( "%s/admin/stores/%s", mockServiceURL, keyPath );
        repoServiceServer.registerException( storePath, "", ApplicationStatus.NOT_FOUND.code() );
        repoServiceServer.registerException( HttpMethod.POST, storePath, NOT_FOUND.code(), "" );
        repoServiceServer.registerException( HttpMethod.PUT, storePath, NOT_FOUND.code(), "" );
        repoServiceServer.expect( HttpMethod.DELETE, storePath, ApplicationStatus.NO_CONTENT.code(), "" );
    }

    public void doConcreteStoreQuery( final StoreKey group, final AbstractRepository... storeInGroup )
            throws Exception
    {
        String queryStorePath =
                format( "%s/admin/stores/query/concretes/inGroup?storeKey=%s&enabled=true", mockServiceURL, group );
        repoServiceServer.expect( queryStorePath, OK.code(),
                                  mapper.writeValueAsString( new StoreListingDTO<>( List.of( storeInGroup ) ) ) );
        // the final slash included in endpoint
        queryStorePath =
                format( "%s/admin/stores/query/concretes/inGroup/?storeKey=%s&enabled=true", mockServiceURL, group );
        repoServiceServer.expect( queryStorePath, OK.code(),
                                  mapper.writeValueAsString( new StoreListingDTO<>( List.of( storeInGroup ) ) ) );
    }

    public void clearConcreteStoreQuery( final StoreKey group )
    {
        String queryStorePath =
                format( "%s/admin/stores/query/concretes/inGroup?storeKey=%s&enabled=true", mockServiceURL, group );
        repoServiceServer.registerException( queryStorePath, "", NOT_FOUND.code() );
        // the final slash included in endpoint
        queryStorePath =
                format( "%s/admin/stores/query/concretes/inGroup/?storeKey=%s&enabled=true", mockServiceURL, group );
        repoServiceServer.registerException( queryStorePath, "", NOT_FOUND.code() );
    }

    public String getStoreJson( final ArtifactStore store )
            throws Exception
    {
        return mapper.writeValueAsString( store );
    }

    public String getJsonForGroupWithConstituents( final StoreKey key, final StoreKey... constituents )
            throws Exception
    {
        if ( key.getType() != group )
        {
            throw new IllegalArgumentException( String.format( "not a valid group: %s", key ) );
        }
        Group g = new Group( key.getPackageType(), key.getName() );
        g.setConstituents( List.of( constituents ) );
        return getStoreJson( g );
    }
}
