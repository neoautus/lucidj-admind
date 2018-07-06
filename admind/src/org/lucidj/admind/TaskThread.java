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

import org.lucidj.api.admind.Task;
import org.lucidj.api.admind.TaskProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;

public class TaskThread extends Thread
{
    private final static Logger log = LoggerFactory.getLogger (TaskThread.class);

    private String identifier;
    private Task task;
    private InputStream in;
    private OutputStream out;
    private TriggeredOutputStream err;

    private TaskThread (String identifier, ThreadGroup group, Task task,
                        InputStream in, OutputStream out, TriggeredOutputStream err)
    {
        super (group, identifier);
        this.identifier = identifier;
        this.task = task;
        this.in = in;
        this.out = out;
        this.err = err;

        log.debug ("New TaskThread (identifier={} group={} task={} in={} out={} err={}",
            identifier, group, task, in, out, err);
    }

    public static TaskThread newInstance (ThreadGroup group, TaskProvider provider, File request)
    {
        String identifier = request.getName ().substring (0, request.getName ().lastIndexOf ('.'));
        String basedir = request.getParent ();
        File response = new File (basedir, identifier + ".out");
        File status = new File (basedir, identifier + ".err");
        InputStream task_in = null;
        OutputStream task_out = null;
        TriggeredOutputStream task_err = null;

        log.debug ("TaskThread.newInstance: request={} (size={})", request.toString(), request.length());

        try
        {
            // TODO: SET PROPER PERMISSIONS ON ALL THESE FILES
            if (!response.createNewFile ())
            {
                // The task was already created
                return (null);
            }

            task_in = new FileInputStream (request);
            task_out = new FileOutputStream (response);
            task_err = new TriggeredOutputStream (status);
        }
        catch (IOException e)
        {
            log.warn ("Exception creating new task {}: {}", identifier, e.toString ());
            closeQuietly (task_in);
            closeQuietly (task_out);
            closeQuietly (task_err);
            return (null);
        }

        // We have all set up to create the serving task
        Task task = provider.createTask (task_in, task_out, task_err,
            getTaskName (identifier), getTaskOptions (identifier));
        TaskThread new_task = new TaskThread (identifier, group, task, task_in, task_out, task_err);
        new_task.setDaemon (true);
        return (new_task);
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

    @Override // Thread
    public void run ()
    {
        try
        {
            if (!task.run ())
            {
                err.write ("Task returned fail status\n".getBytes ());
            }
        }
        catch (Throwable t)
        {
            log.warn ("Task {} throwed {}", identifier, t.toString ());
            t.printStackTrace (new PrintStream (err));
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
                // The error file presence indicates thread finished.
                // An empty error file indicates success.
                err.touch ();
                err.close ();
            }
            catch (Exception ignore) {};
        }
    }

    private static void closeQuietly (Closeable c)
    {
        if (c != null) try
        {
            c.close ();
        }
        catch (IOException e) {};
    }

    static class TriggeredOutputStream extends OutputStream
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
            super.close ();
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

        private OutputStream get_shadow ()
            throws IOException
        {
            if (shadow_os == null)
            {
                shadow_os = Files.newOutputStream (file.toPath ());
            }
            return (shadow_os);
        }

        public void touch ()
            throws IOException
        {
            // Ensure the file is created
            get_shadow ();
        }

        @Override // OutputStream
        public void write (byte[] b)
            throws IOException
        {
            get_shadow ().write (b);
        }

        @Override // OutputStream
        public void write (byte[] b, int off, int len)
            throws IOException
        {
            get_shadow ().write (b, off, len);
        }

        @Override // OutputStream
        public void write (int b)
            throws IOException
        {
            get_shadow ().write (b);
        }
    }
}

// EOF
