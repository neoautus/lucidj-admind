/*
 * Copyright 2018 NEOautus Ltd. (http://neoautus.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.lucidj.examples.toupper;

import org.lucidj.api.admind.Task;
import org.lucidj.api.admind.TaskProvider;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Dictionary;
import java.util.Hashtable;

public class ToUpperProvider implements TaskProvider, BundleActivator
{
    private final static Logger log = LoggerFactory.getLogger (ToUpperProvider.class);

    private ServiceRegistration<TaskProvider> provider_registration;

    @Override // TaskProvider
    public Task createTask (InputStream in, OutputStream out, OutputStream err, String locator, String... options)
    {
        log.info ("New ToUpperTask: locator={} options={}", locator, (Object)options);
        return (new ToUpperTask (in, out, err, locator, options));
    }

    @Override // BundleActivator
    public void start (BundleContext bundleContext)
        throws Exception
    {
        Dictionary<String, Object> props = new Hashtable<>();
        props.put (TaskProvider.NAME_FILTER, "toupper");
        provider_registration = bundleContext.registerService (TaskProvider.class, this, props);
        log.info ("ToUpperProvider started ({})", provider_registration);
    }

    @Override // BundleActivator
    public void stop (BundleContext bundleContext)
        throws Exception
    {
        log.info ("ToUpperProvider stopping ({})", provider_registration);
        provider_registration.unregister ();
    }
}

// EOF
