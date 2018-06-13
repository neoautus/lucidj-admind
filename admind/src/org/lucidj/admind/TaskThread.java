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

package org.lucidj.admind;

import org.lucidj.api.admind.TaskProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

public class TaskThread extends Thread
{
    private final static Logger log = LoggerFactory.getLogger (TaskThread.class);

    private TaskProvider provider;
    private Runnable task;
    private File in_file;
    private InputStream in;
    private OutputStream out;
    private TriggeredOutputStream err;

    public TaskThread (ThreadGroup group, TaskProvider provider, File request)
    {
        super (group, request.getName());
        this.provider = provider;
        this.in_file = request;
        log.info ("New TaskThread (group={} provider={} request={} (size={})",
            group, provider, request, request.length());
    }

    public static boolean validTaskIdentifier (String identifier)
    {
        // Valid patterns are:
        //
        // 1) <task name>--<unique task id>
        // Examples:
        //    echo--12345
        //    a.b.c.uppercase--123xyz456
        //    _very_strange_--veryUNique
        // 2) <task name>--<option1>[-<optionN>*]--<unique task id>
        // Examples:
        //    echo--reverse--23456
        //    com.zeus.create--mesons--a1b2c3d4
        //    _odd-name-with-dashes--xml-ordered-utf8--1bf89c33
        //
        return (identifier.contains ("--"));  // Dummy test
    }

    public static String getTaskName (String identifier)
    {
        return (validTaskIdentifier (identifier)? identifier.substring (0, identifier.indexOf ("--")): null);
    }

    public static String[] getTaskOptions (String identifier)
    {
        return (new String [0]);
    }

    @Override
    public void start ()
    {
        String basedir = in_file.getParent ();
        String identifier = in_file.getName ().substring (0, in_file.getName ().lastIndexOf ('.'));
        String name = getTaskName (identifier);
        String[] options = getTaskOptions (identifier);

        log.info ("basedir={} filename={}", basedir, identifier);

        try
        {
            // TODO: SET PROPER PERMISSIONS ON ALL THESE FILES
            File out_file = new File (basedir, identifier + ".out");
            File err_file = new File (basedir, identifier + ".err");
            in = new FileInputStream (in_file);
            out = new FileOutputStream (out_file);
            err = new TriggeredOutputStream (err_file);
            task = provider.createTask (in, out, err, name, options);
            setDaemon (true);
            super.start ();
        }
        catch (FileNotFoundException e)
        {
            log.error ("Exception creating task {}: ", identifier, e);
        }
    }

    @Override
    public void run ()
    {
        try
        {
            task.run ();
        }
        catch (Throwable t)
        {

        }
        finally
        {
            try
            {
                in.close ();
            }
            catch (Exception ignore) {};

            try
            {
                out.close ();
            }
            catch (Exception ignore) {};

            try
            {
                err.close ();
            }
            catch (Exception ignore) {};
        }
    }

    class TriggeredOutputStream extends OutputStream
    {
        private OutputStream shadow_os;     // The real OutputStream is only created if data is written
        private File file;

        public TriggeredOutputStream (File file)
        {
            super ();
            this.file = file;
        }

        @Override // OutputStream
        public void close ()
            throws IOException
        {
            if (shadow_os != null)
            {
                shadow_os.close ();
            }
            super.close();
        }

        @Override // OutputStream
        public void flush ()
            throws IOException
        {
            if (shadow_os != null)
            {
                shadow_os.flush ();
            }
        }

        private OutputStream getShadow ()
                throws IOException
        {
            if (shadow_os == null)
            {
                shadow_os = new FileOutputStream (file);
            }
            return (shadow_os);
        }

        @Override // OutputStream
        public void write (byte[] b)
            throws IOException
        {
            getShadow ().write (b);
        }

        @Override // OutputStream
        public void write(byte[] b, int off, int len)
            throws IOException
        {
            getShadow ().write (b, off, len);
        }

        @Override // OutputStream
        public void write (int b)
            throws IOException
        {
            getShadow ().write (b);
        }
    }
}

// EOF
