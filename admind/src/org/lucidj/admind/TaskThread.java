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
import org.lucidj.ext.admind.AdmindUtil;
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
    private OutputStream err;
    private File temp_file;
    private File err_file;

    private TaskThread (String identifier, ThreadGroup group, Task task,
                        File temp_file, File err_file,
                        InputStream in, OutputStream out, OutputStream err)
    {
        super (group, identifier);
        this.identifier = identifier;
        this.task = task;
        this.temp_file = temp_file;
        this.err_file = err_file;
        this.in = in;
        this.out = out;
        this.err = err;

        log.debug ("New TaskThread (identifier={} group={} task={} in={} out={} err={}",
            identifier, group, task, in, out, err);
    }

    public static TaskThread newInstance (ThreadGroup group, TaskProvider provider, File request_file)
    {
        String identifier = request_file.getName ().substring (0, request_file.getName ().lastIndexOf ('.'));
        String request = request_file.getAbsolutePath ();
        File response_file = AdmindUtil.responseFile (request);
        File err_file = AdmindUtil.statusFile (request);
        File temp_file = AdmindUtil.tempFile (request);
        InputStream task_in = null;
        OutputStream task_out = null;
        OutputStream task_err = null;

        log.debug ("TaskThread.newInstance: request={} (size={})", request_file, request_file.length ());

        try
        {
            if (!response_file.createNewFile ())
            {
                // The task was already created
                return (null);
            }

            // Create and/or set proper permissions
            AdmindUtil.createAndFixPermissions (response_file);
            AdmindUtil.createAndFixPermissions (temp_file);

            task_in = Files.newInputStream (request_file.toPath ());
            task_out = Files.newOutputStream (response_file.toPath ());
            task_err = Files.newOutputStream (temp_file.toPath ());
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
        TaskThread new_task = new TaskThread (identifier, group, task,
            temp_file, err_file, task_in, task_out, task_err);
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
            closeQuietly (in);
            closeQuietly (out);
            closeQuietly (err);

            // Only after all finished rename .tmp file to valid
            // status file with .err extension. If we got no errors
            // then err file will be empty (0 length).
            temp_file.renameTo (err_file);
            AdmindUtil.createAndFixPermissions (err_file);
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
}

// EOF
