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

package org.lucidj.admind.builtin;

import org.lucidj.api.admind.Task;
import org.osgi.framework.BundleContext;
import org.osgi.framework.launch.Framework;

import java.io.InputStream;
import java.io.OutputStream;

public class ShutdownTask implements Task
{
    public final static String NAME = "shutdown";

    private BundleContext context;
    private InputStream in;
    private OutputStream out;
    private OutputStream err;

    public ShutdownTask (BundleContext context, InputStream in, OutputStream out, OutputStream err,
                         String name, String... options)
    {
        this.context = context;
        this.in = in;
        this.out = out;
        this.err = err;
    }

    @Override // Task
    public boolean run ()
        throws Exception
    {
        context.getBundle (0).adapt (Framework.class).stop ();
        return (true);
    }
}

// EOF
