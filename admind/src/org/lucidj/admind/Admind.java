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

import org.lucidj.admind.builtin.EchoTask;
import org.lucidj.admind.builtin.ShutdownTask;
import org.lucidj.admind.builtin.StartlevelTask;
import org.lucidj.admind.shared.AdmindUtil;
import org.lucidj.api.admind.Task;
import org.lucidj.api.admind.TaskProvider;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class Admind implements TaskProvider
{
    private final static Logger log = LoggerFactory.getLogger (Admind.class);

    private String admind_dir;
    private boolean cleanup_admind_dir;

    private ThreadGroup admind_group;
    private Thread admind_main_thread;
    private WatchService watch_service;

    private BundleContext context;
    private ServiceTracker<TaskProvider, TaskProvider> service_tracker;
    private Map<String, TaskProvider> available_tasks = new ConcurrentHashMap<> ();

    public Admind (BundleContext context)
    {
        this.context = context;
        init_builtin_tasks ();
        service_tracker = new TaskProviderTracker (context);
    }

    private void init_builtin_tasks ()
    {
        available_tasks.put (EchoTask.NAME, this);
        available_tasks.put (StartlevelTask.NAME, this);
        available_tasks.put (ShutdownTask.NAME, this);
    }

    @Override // TaskProvider
    public Task createTask (InputStream in, OutputStream out, OutputStream err, String locator, String... options)
    {
        log.info ("Built-in task: locator={} options={}", locator, (Object)options);

        switch (locator)
        {
            case ShutdownTask.NAME:
            {
                return (new ShutdownTask (context, in, out, err, locator, options));
            }
            case StartlevelTask.NAME:
            {
                return (new StartlevelTask (context, in, out, err, locator, options));
            }
            case EchoTask.NAME:
            default:
            {
                return (new EchoTask (in, out, err, locator, options));
            }
        }
    }

    private void shutdown_watch_service ()
    {
        if (watch_service != null)
        {
            try
            {
                watch_service.close ();
            }
            catch (IOException ignore) {};

            watch_service = null;
        }
    }

    private boolean init_watch_service ()
    {
        try
        {
            watch_service = FileSystems.getDefault ().newWatchService ();

            Path path = Paths.get (admind_dir);

            path.register (watch_service,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
            return (true);
        }
        catch (IOException e)
        {
            shutdown_watch_service ();
            log.warn ("Exception initializing WatchService: {}", e.toString());
            return (false);
        }
    }

    private void assign_task (File req_file)
    {
        log.debug ("assign_task: {} size={}", req_file, req_file.length ());

        String identifier = req_file.getName ().substring (0, req_file.getName ().lastIndexOf ('.'));
        String name = TaskThread.getTaskName (identifier);
        String err_message = null;

        if (name == null)
        {
            err_message = "Invalid task identifier";
        }
        else
        {
            if (available_tasks.containsKey (name))
            {
                TaskProvider provider = available_tasks.get (name);
                Thread task_thread = TaskThread.newInstance (admind_group, provider, req_file);
                log.debug ("Task {} => {}", identifier, task_thread);

                if (task_thread != null)
                {
                    task_thread.start ();
                }
            }
            else
            {
                err_message = "Task '" + name + "' not found";
            }
        }

        if (err_message != null)
        {
            String request = req_file.getAbsolutePath ();
            File tmp_file = AdmindUtil.tempFile (request);
            File err_file = AdmindUtil.statusFile (request);

            try (PrintStream pw = new PrintStream (tmp_file))
            {
                pw.println (err_message + ": " + req_file.getName ());
            }
            catch (IOException e)
            {
                log.warn ("Exception creating err file {}: {}", tmp_file.getName (), e.toString ());
            }
            tmp_file.renameTo (err_file);
            AdmindUtil.createAndFixPermissions (err_file);
        }
    }

    public void watch_admind_dir ()
    {
        log.info ("AdminD started on {}", admind_dir);

        while (!admind_main_thread.isInterrupted ())
        {
            if (AdmindUtil.getAdmindDir () == null)
            {
                if (admind_dir != null)
                {
                    log.warn ("Directory {} was deleted", admind_dir);
                }
                shutdown_watch_service ();
                admind_dir = null;

                if (context.getBundle (0).getState () != Bundle.ACTIVE)
                {
                    log.info ("Stopping AdminD due to framework shutdown");
                    break;
                }

                try
                {
                    admind_dir = AdmindUtil.setupAdmindDir ();
                    log.info ("Directory {} was recreated", admind_dir);
                }
                catch (IOException e)
                {
                    log.warn ("Exception getting AdminD directory: {}", e.toString (), e);
                }
            }

            if (watch_service == null)
            {
                if (!init_watch_service ())
                {
                    log.warn ("Unable to activate WatchService: {}", admind_dir);
                }
            }

            try
            {
                if (watch_service == null)
                {
                    // If the service is not available, wait and retry activation
                    Thread.sleep (1000);
                    continue;
                }

                WatchKey watch_key = watch_service.poll (1, TimeUnit.SECONDS);

                if (watch_key != null)
                {
                    for (WatchEvent<?> event: watch_key.pollEvents ())
                    {
                        if (event.context () instanceof Path)
                        {
                            File req_file = new File (admind_dir, event.context ().toString ());

                            if (!req_file.getName ().endsWith (AdmindUtil.REQUEST_SUFFIX)
                                || req_file.length () == 0)
                            {
                                // We ignore empty files
                                continue;
                            }

                            if (event.kind ().equals (StandardWatchEventKinds.ENTRY_CREATE)
                                || event.kind ().equals (StandardWatchEventKinds.ENTRY_MODIFY))
                            {
                                assign_task (req_file);
                            }
                        }
                    }
                    watch_key.reset ();
                }
            }
            catch (ClosedWatchServiceException | InterruptedException e)
            {
                // We should break loop
                break;
            }
            catch (Throwable t)
            {
                try
                {
                    // This will fail if this bundle is uninstalled (zombie)
                    context.getBundle ();
                }
                catch (IllegalStateException e)
                {
                    // This bundle has been uninstalled, exiting loop
                    break;
                }
                log.warn ("AdminD exception: " + t.toString ());
            }
        }
        log.info ("AdminD stopped");
    }

    public boolean start ()
    {
        try
        {
            // Is there an admind dir ready to use?
            if ((admind_dir = AdmindUtil.getAdmindDir ()) == null)
            {
                // No, we need to create it and delete later
                cleanup_admind_dir = true;
                admind_dir = AdmindUtil.setupAdmindDir ();
                AdmindUtil.startKeepAlive ();
                log.info ("Directory {} was created", admind_dir);
            }
        }
        catch (IOException e)
        {
            // We don't abort the start so we can retry later
            log.warn ("Exception starting AdminD: {}", e.toString(), e);
        }

        service_tracker.open ();
        admind_group = new ThreadGroup (this.getClass ().getSimpleName ());
        admind_main_thread = new Thread (admind_group, new Runnable()
        {
            @Override
            public void run()
            {
                watch_admind_dir ();
            }
        }, "Polling [" + admind_dir + "]");
        admind_main_thread.start ();
        return (true);
    }

    public void stop ()
    {
        if (watch_service == null)
        {
            return;
        }

        try
        {
            // Stop things, wait at most 10 secs for clean stop
            shutdown_watch_service ();
            service_tracker.close ();

            if (cleanup_admind_dir)
            {
                // We only remove the dir if we created it
                AdmindUtil.stopKeepAlive ();
                AdmindUtil.cleanupAdmindDir ();
            }

            admind_main_thread.interrupt ();
            admind_main_thread.join (10000);
            // TODO: DESTROY admind_group
        }
        catch (IOException | InterruptedException ignore) {};
    }

    class TaskProviderTracker extends ServiceTracker<TaskProvider, TaskProvider>
    {
        public TaskProviderTracker (BundleContext context)
        {
            super (context, TaskProvider.class.getName (), null);
        }

        @Override // ServiceTracker
        public TaskProvider addingService (ServiceReference<TaskProvider> reference)
        {
            TaskProvider service = context.getService (reference);
            String name = (String)reference.getProperty (TaskProvider.NAME_FILTER);

            if (name != null)
            {
                log.info ("Registering task provider: {} ({})", name, service);
                available_tasks.put (name, service);
            }
            else
            {
                log.error ("Locator missing on task provider {} ", service);
            }
            return (service);
        }

        @Override // ServiceTracker
        public void removedService (ServiceReference<TaskProvider> reference, TaskProvider service)
        {
            String name = (String)reference.getProperty (TaskProvider.NAME_FILTER);
            log.info ("Unregistering task provider: {} ({})", name, service);
            available_tasks.remove (name);
            super.removedService (reference, service);
        }
    }
}

// EOF
