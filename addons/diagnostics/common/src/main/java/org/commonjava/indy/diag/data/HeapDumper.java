/**
 * Copyright (C) 2013~2019 Red Hat, Inc.
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
package org.commonjava.indy.diag.data;

import com.sun.management.HotSpotDiagnosticMXBean;

import javax.enterprise.context.ApplicationScoped;
import javax.management.MBeanServer;
import java.io.IOException;
import java.lang.management.ManagementFactory;

@ApplicationScoped
class HeapDumper
{
    // This is the name of the HotSpot Diagnostic MBean
    private static final String HOTSPOT_BEAN_NAME = "com.sun.management:type=HotSpotDiagnostic";

    private static final String INDY_DUMP_TEMPLATE = "/var/lib/indy/indy-%s.hprof";

    private static volatile HotSpotDiagnosticMXBean hotspotMBean;

    HeapDumper()
    {
        initHotspotMBean();
    }

    String dumpIndyHeap( boolean live )
    {
        try
        {
            final long currentTime = System.currentTimeMillis();
            final String fileName = String.format( "/var/lib/indy/indy-%s.hprof", currentTime );
            hotspotMBean.dumpHeap( fileName, live );
            return fileName;
        }
        catch ( IOException exp )
        {
            throw new RuntimeException( exp );
        }
    }

    private static void initHotspotMBean()
    {
        if ( hotspotMBean == null )
        {
            synchronized ( HeapDumper.class )
            {
                if ( hotspotMBean == null )
                {
                    hotspotMBean = getHotspotMBean();
                }
            }
        }
    }

    private static HotSpotDiagnosticMXBean getHotspotMBean()
    {
        try
        {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            return ManagementFactory.newPlatformMXBeanProxy( server, HOTSPOT_BEAN_NAME, HotSpotDiagnosticMXBean.class );
        }
        catch ( RuntimeException re )
        {
            throw re;
        }
        catch ( Exception exp )
        {
            throw new RuntimeException( exp );
        }
    }

}