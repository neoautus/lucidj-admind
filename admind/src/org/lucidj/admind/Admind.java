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
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class Admind
{
    private final static Logger log = LoggerFactory.getLogger (Admind.class);

    private final static String BASE_DIRECTORY = "jvm_admind_";

    private static String admind_dir;
    private ThreadGroup admind_group;
    private Thread admind_main_thread;

    private WatchService watch_service;

    private String tmp_dir;
    private String jvm_id;

    private BundleContext context;
    private ServiceTracker<TaskProvider, TaskProvider> service_tracker;

    public Admind (BundleContext context)
    {
        this.context = context;
        service_tracker = new TaskProviderTracker(context);

        tmp_dir = System.getProperty ("java.io.tmpdir");

        // Solaris odd tmp_dir?
        if (tmp_dir == null || "/var/tmp/".equals (tmp_dir))
        {
            tmp_dir = "/tmp/";
        }

        if (tmp_dir.charAt (tmp_dir.length () - 1) != File.separatorChar)
        {
            tmp_dir = tmp_dir + File.separatorChar;
        }
    }

    private boolean is_valid_dir (String dir)
    {
        File dir_file = new File (dir);

        return (dir_file.isDirectory ()
                && dir_file.canRead ()
                && dir_file.canWrite ()
                && dir_file.canExecute ());
    }

    private boolean mksane (String dir)
    {
        File basedir = new File (dir);

        if (!basedir.exists ())
        {
            if (!basedir.mkdir ())
            {
                return (false);
            }
        }

        // TODO: HANDLE PERMISSIONS ON WINDOZE TOO
        Set<PosixFilePermission> perms = new HashSet<> ();
        perms.add (PosixFilePermission.OWNER_READ);
        perms.add (PosixFilePermission.OWNER_WRITE);
        perms.add (PosixFilePermission.OWNER_EXECUTE);

        try
        {
            Files.setPosixFilePermissions (basedir.toPath (), perms);
        }
        catch (IOException e)
        {
            return (false);
        }

        // We have a sane directory (u+rwx,go-rwx)
        return (true);
    }

    private boolean init_jvmctl_dir ()
    {
        String basedir = tmp_dir + BASE_DIRECTORY + System.getProperty ("user.name");

        if (is_valid_dir (basedir) || mksane (basedir))
        {
            jvm_id = ManagementFactory.getRuntimeMXBean ().getName ();
            admind_dir = basedir + File.separator + jvm_id.replaceAll ("\\p{P}", "_");

            if (mksane (admind_dir))
            {
                return (true);
            }
        }
        return (false);
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
            log.warn ("Exception initializing WatchService: {}", e.toString());
            return (false);
        }
    }

    public void watch_jvmctl_dir ()
    {
        log.info ("AdminD started");

        while (!admind_main_thread.isInterrupted ())
        {
//            if (!is_valid_dir (admind_dir))
//            {
//                try
//                {
//                    watch_service.close();
//                }
//                catch (IOException e)
//                {
//                    log.error ("Exception closing {}", admind_dir, e);
//                }
//            }

            if (admind_dir == null)
            {
                if (!init_jvmctl_dir ())
                {
                    log.warn ("JVM control directory not available: {}", admind_dir);
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
                        log.info ("Event: kind={} context={}", event.kind (), event.context ());

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
                log.error ("AdminD exception: " + t.toString ());
            }
        }

        log.info ("AdminD stopped");
    }

    public boolean start ()
    {
        service_tracker.open ();
        admind_group = new ThreadGroup ("jvmctl");
        admind_main_thread = new Thread(admind_group, new Runnable()
        {
            @Override
            public void run()
            {
                watch_jvmctl_dir ();
            }
        }, "polling");
        admind_main_thread.setName (this.getClass ().getSimpleName ());
        admind_main_thread.start ();
        return (true);
    }

    private void cleanup_admind_jvm_dir ()
    {
        try
        {
            log.info ("Removing " + admind_dir + " ...");

            Files.walkFileTree (Paths.get (admind_dir), new SimpleFileVisitor<Path> ()
            {
                @Override
                public FileVisitResult postVisitDirectory (Path dir, IOException exc)
                        throws IOException
                {
                    Files.delete(dir);
                    return (FileVisitResult.CONTINUE);
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException
                {
                    Files.delete (file);
                    return (FileVisitResult.CONTINUE);
                }
            });

            log.info ("Directory " + admind_dir + " removed.");
        }
        catch (IOException ignore) {};
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
            watch_service.close ();
            service_tracker.close ();
            cleanup_admind_jvm_dir ();
            admind_main_thread.interrupt ();
            admind_main_thread.join (10000);
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
            String locator = (String)reference.getProperty (TaskProvider.LOCATOR_FILTER);

            if (locator != null)
            {
                log.info ("Registering task provider: {} ({})", locator, service);
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
            String locator = (String)reference.getProperty (TaskProvider.LOCATOR_FILTER);
            log.info ("Unregistering task provider: {} ({})", locator, service);

            super.removedService (reference, service);
        }
    }
}

// EOF
