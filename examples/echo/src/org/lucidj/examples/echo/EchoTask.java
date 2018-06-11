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

package org.lucidj.examples.echo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;

public class EchoTask implements Runnable
{
    private InputStream in;
    private OutputStream out;
    private OutputStream err;

    public EchoTask (InputStream in, OutputStream out, OutputStream err, String locator, String... options)
    {
        this.in = in;
        this.out = out;
        this.err = err;
    }

    @Override
    public void run()
    {
        try
        {
            for (int ch; (ch = in.read ()) != -1; out.write (Character.toUpperCase (ch)));
        }
        catch (IOException e)
        {
            e.printStackTrace (new PrintWriter (err));
        }
    }
}

// EOF
