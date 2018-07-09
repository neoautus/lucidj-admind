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

package org.lucidj.ext.admind;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Date;
import java.util.HashSet;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

public class AdmindUtil
{
    private final static String BASE_DIRECTORY = "jvm_admind_";
    private final static String SERVER_NAME_PROPERTY = "server.name";
    private final static String SERVER_JVMID_PROPERTY = "server.jvmid";

    private final static int JVM_LINGER_TIME_MS = 5000;
    private final static int DEFAULT_WAIT_TIMEOUT_MS = 15000;

    public final static String REQUEST_SUFFIX = ".run";
    public final static String RESPONSE_SUFFIX = ".out";
    public final static String STATUS_SUFFIX = ".err";
    public final static String TEMP_SUFFIX = ".tmp";

    public static int ASYNC_ERROR = 0;
    public static int ASYNC_PENDING = 1;
    public static int ASYNC_RUNNING = 2;
    public static int ASYNC_READY = 3;
    public static int ASYNC_GONE = 4;

    private static String jvm_id;
    private static String tmp_dir;
    private static String root_admind_dir;
    private static String admind_dir;
    private static String default_server_name;
    private static Properties server_properties;

    private static Thread cleanup_thread_hook = null;
    private static Thread keepalive_thread = null;

    private static Random random = new Random ();

    static
    {
        // Temp directory (hopefully tmpfs or other backed in RAM)
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

        // JVM local ID (usually something like <pid>@<hostname>)
        jvm_id = ManagementFactory.getRuntimeMXBean ().getName ();

        // Base AdminD directory for _all_ JVMs on system (usually /tmp/jvm_admind_<username>)
        root_admind_dir = tmp_dir + BASE_DIRECTORY + System.getProperty ("user.name");

        // Our local JVM-specific AdminD directory (like /tmp/jvm_admind_spock/31415_ncc1701)
        admind_dir = root_admind_dir + File.separator + jvm_id.replaceAll ("\\p{P}", "_");

        // Each user have its own default server named like 'server_{user.name}'
        default_server_name = System.getProperty (SERVER_NAME_PROPERTY, "server_" + System.getProperty ("user.name"));
    }

    private static boolean is_valid_dir (String dir)
    {
        File dir_file = new File (dir);

        // Must be a directory with RWX access to the owner (us)
        return (dir_file.isDirectory ()
                && dir_file.canRead ()
                && dir_file.canWrite ()
                && dir_file.canExecute ());
    }

    public static String getAdmindDir ()
    {
        // Allows us to detect whether AdminD dir is already initialized
        return (is_valid_dir (admind_dir)? admind_dir: null);
    }

    //=================================================================================================================
    // DIRECTORY CREATION
    //=================================================================================================================

    private static void fix_permissions (File file_or_dir)
        throws IOException
    {
        // Test for modern systems
        if (System.getProperty ("os.name", "ManchesterBaby").startsWith ("Windows"))
        {
            // TODO: FIX PERMISSIONS WINDOWS WAY
        }
        else // Default: Unix/Linux way
        {
            // Select permissions as usable only to owner (u+rw[x],go-rw[x])
            Set<PosixFilePermission> perms = new HashSet<> ();
            perms.add (PosixFilePermission.OWNER_READ);
            perms.add (PosixFilePermission.OWNER_WRITE);

            if (file_or_dir.isDirectory ())
            {
                // Directories need u+x also
                perms.add (PosixFilePermission.OWNER_EXECUTE);
            }
            Files.setPosixFilePermissions (file_or_dir.toPath (), perms);
        }
    }

    private static void mksane (String dir)
        throws IOException
    {
        File fdir = new File (dir);

        // Check if the directory already exists...
        if (!fdir.exists ())
        {
            // ...and create it if not
            if (!fdir.mkdir ())
            {
                throw (new IOException ("Error creating directory: " + dir));
            }
        }

        // Set directory permissions as usable only to owner (u+rwx,go-rwx)
        fix_permissions (fdir);
    }

    public static String setupAdmindDir (boolean setupShutdownHook)
        throws IOException
    {
        mksane (root_admind_dir);
        mksane (admind_dir);

        // Double-check the availability of admind_dir
        if (!is_valid_dir (admind_dir))
        {
            throw (new IOException ("Unknown error trying to setup: " + admind_dir));
        }

        // Create the server data file with owner-only permissions
        File serverdata_file = new File (admind_dir, default_server_name + ".properties");
        if (!serverdata_file.createNewFile ())
        {
            throw (new IOException ("Unable to create file: " + serverdata_file));
        }
        fix_permissions (serverdata_file);

        // Store system properties
        String comments = "Properties for " + jvm_id;
        System.setProperty (SERVER_NAME_PROPERTY, default_server_name);     // Make sure server.name is present
        System.setProperty (SERVER_JVMID_PROPERTY, jvm_id);                 // and also server.jvmid
        System.getProperties ().store (new FileOutputStream (serverdata_file), comments);

        if (setupShutdownHook && cleanup_thread_hook == null)
        {
            // We haven't set a shutdown hook yet
            cleanup_thread_hook = new Thread ()
            {
                public void run ()
                {
                    try
                    {
                        cleanupAdmindDir ();
                    }
                    catch (IOException e)
                    {
                        String lastwords =
                            "On "
                            + (new Date ().toString ())
                            + " error removing AdminD directory: "
                            + admind_dir;

                        try
                        {
                            FileOutputStream postmortem =
                                new FileOutputStream (root_admind_dir + "/postmortem.log", true);
                            Exception farewell = new Exception (lastwords, e.getCause ());
                            farewell.printStackTrace (new PrintWriter (postmortem));
                        }
                        catch (IOException die_peacefully) {};
                    }
                }
            };
            Runtime.getRuntime ().addShutdownHook (cleanup_thread_hook);
        }
        return (admind_dir);
    }

    public static void startKeepAlive ()
    {
        if (keepalive_thread != null)
        {
            // Already set
            return;
        }

        // The keepalive thread simple updates every second the last modified time
        // for the serverdata file at: /tmp/jvm_admind_{user.name}/{server.name}.properties
        keepalive_thread = new Thread ("AdminD Keep-Alive")
        {
            @Override
            public void run ()
            {
                while (keepalive_thread != null && !keepalive_thread.isInterrupted ())
                {
                    try
                    {
                        Path serverdata_path = Paths.get (admind_dir, default_server_name + ".properties");
                        Files.setLastModifiedTime (serverdata_path, FileTime.fromMillis (System.currentTimeMillis ()));
                        Thread.sleep (1000);
                    }
                    catch (IOException | InterruptedException ignore) {};
                }
            }
        };
        keepalive_thread.setDaemon (true);
        keepalive_thread.start ();
    }

    public static void stopKeepAlive ()
    {
        keepalive_thread.interrupt ();
        keepalive_thread = null;
    }

    public static String setupAdmindDir ()
        throws IOException
    {
        return (setupAdmindDir (false));
    }

    public static void cleanupAdmindDir ()
        throws IOException
    {
        if (!is_valid_dir (admind_dir))
        {
            // The directory doesn't exists anyway
            return;
        }
        deleteDirTree (admind_dir, false);
    }

    public static void deleteDirTree (String root_dir, boolean preserveRoot)
        throws IOException
    {
        final Path root_path = Paths.get (root_dir);

        Files.walkFileTree (root_path, new SimpleFileVisitor<Path> ()
        {
            @Override
            public FileVisitResult postVisitDirectory (Path dir, IOException exc)
                throws IOException
            {
                if (!preserveRoot || !root_path.equals (dir))
                {
                    Files.delete (dir);
                }
                return (FileVisitResult.CONTINUE);
            }

            @Override
            public FileVisitResult visitFile (Path file, BasicFileAttributes attrs)
                throws IOException
            {
                Files.delete (file);
                return (FileVisitResult.CONTINUE);
            }
        });
    }

    //=================================================================================================================
    // DIRECTORY DISCOVERY
    //=================================================================================================================

    public static String getServerName ()
    {
        return (default_server_name);
    }

    public static Properties getServerProperties ()
    {
        return (server_properties);
    }

    public static String initAdmindDir ()
    {
        return (initAdmindDir (null));
    }

    public static String initAdmindDir (String server_name)
    {
        if (server_name == null)
        {
            server_name = default_server_name;
        }

        File user_jvms = new File (root_admind_dir);
        File[] jvm_dir_list = user_jvms.listFiles ();

        if (jvm_dir_list == null)
        {
            return (null);
        }

        for (File jvm_dir: jvm_dir_list)
        {
            if (!jvm_dir.isDirectory ())
            {
                continue;
            }

            File serverdata_file = new File (jvm_dir, server_name + ".properties");
            server_properties = new Properties ();

            try
            {
                FileTime last_modified = Files.getLastModifiedTime (Paths.get (serverdata_file.toURI ()));

                if (last_modified.toMillis () + JVM_LINGER_TIME_MS < System.currentTimeMillis ())
                {
                    // The JVM this server_properties file is referring has not touched the
                    // file for 5 seconds. Probably the JVM has gone and left admind adrift.
                    continue;
                }

                server_properties.load (new FileInputStream (serverdata_file));
            }
            catch (IOException ignore) {};

            if (server_name.equals (server_properties.getProperty (SERVER_NAME_PROPERTY)))
            {
                String test_dir = jvm_dir.getPath ();

                if (is_valid_dir (test_dir))
                {
                    admind_dir = test_dir;
                    return (admind_dir);
                }
            }
        }
        return (null);
    }

    //=================================================================================================================
    // ASYNCHRONOUS TASKS
    //=================================================================================================================

    public static String asyncInvoke (String task, String data, String... options)
    {
        String dir = getAdmindDir ();

        if (dir == null)
        {
            return (null);
        }

        StringBuilder sb = new StringBuilder ();
        sb.append(task);

        if (options != null && options.length > 0)
        {
            sb.append ("-");

            for (String option: options)
            {
                sb.append('-');
                sb.append(option);
            }
        }
        sb.append("--");

        int base_identifier_len = sb.length ();
        File request = null;

        for (int attemps = 0; attemps < 10; attemps++)
        {
            sb.setLength (base_identifier_len);
            sb.append (Long.toHexString (random.nextLong ()));
            sb.append (REQUEST_SUFFIX);
            request = new File (dir, sb.toString ());

            try
            {
                if (request.createNewFile ())
                {
                    fix_permissions (request);
                    break;
                }
                request = null;
            }
            catch (IOException ignore) {};
        }

        if (request == null)
        {
            return (null);
        }

        try (OutputStream os = new FileOutputStream (request))
        {
            os.write (data.getBytes (StandardCharsets.UTF_8));
            return (request.getPath ());
        }
        catch (IOException ignore) {};

        // Unable to process the request
        request.delete ();
        return (null);
    }

    public static File requestFile (String request)
    {
        return (new File (request));
    }

    public static File responseFile (String request)
    {
        return (new File (request.substring (0, request.lastIndexOf (REQUEST_SUFFIX)) + RESPONSE_SUFFIX));
    }

    public static File createAndFixPermissions (File file)
    {
        try
        {
            if (!file.exists ())
            {
                file.createNewFile ();
            }

            // Ensure we always have u+rw/go-rwx set
            fix_permissions (file);
            return (file);
        }
        catch (IOException e)
        {
            return (null);
        }
    }

    public static File statusFile (String request)
    {
        return (new File (request.substring (0, request.lastIndexOf (REQUEST_SUFFIX)) + STATUS_SUFFIX));
    }

    public static File tempFile (String request)
    {
        return (new File (request.substring (0, request.lastIndexOf (REQUEST_SUFFIX)) + TEMP_SUFFIX));
    }

    private static String get_contents (File file)
    {
        try
        {
            byte[] contents = Files.readAllBytes (file.toPath ());
            return (new String (contents, StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            return (null);
        }
    }

    public static int asyncStatus (String request)
    {
        File status = statusFile (request);

        // Status -> task finished
        if (status.exists ())
        {
            // Status empty = success, not empty = error message
            return (status.length () == 0? ASYNC_READY: ASYNC_ERROR);
        }
        else if (responseFile (request).exists ())
        {
            // Response -> task is running
            return (ASYNC_RUNNING);
        }
        else if (requestFile (request).exists ())
        {
            // Only request exists
            return (ASYNC_PENDING);
        }
        else
        {
            // No request
            return (ASYNC_GONE);
        }
    }

    public static int asyncPoll (String request)
    {
        int status = asyncStatus (request);

        if (status == ASYNC_READY || status == ASYNC_ERROR)
        {
            return (status);
        }

        try
        {
            // We are doing things on filesystem level, 1ms is good enough
            Thread.sleep (1);
        }
        catch (InterruptedException ignore) {};

        return (asyncStatus (request));
    }

    public static int asyncWait (String request, long timeout_ms, int awaited_status)
    {
        long timeout = System.currentTimeMillis () + timeout_ms;
        int status;

        do
        {
            if (timeout < System.currentTimeMillis ())
            {
                // Force cleanup
                asyncError (request);
                return (ASYNC_ERROR);
            }
            status = asyncPoll (request);
        }
        while (status != awaited_status && status != ASYNC_ERROR && status != ASYNC_GONE);
        return (status);
    }

    public static int asyncWait (String request, long timeout_ms)
    {
        return (asyncWait (request, timeout_ms, ASYNC_READY));
    }

    public static int asyncWait (String request)
    {
        return (asyncWait (request, DEFAULT_WAIT_TIMEOUT_MS));
    }

    public static boolean asyncFinished (String request)
    {
        return (statusFile (request).exists ());
    }

    private static void remove_transaction (String request)
    {
        requestFile (request).delete ();
        responseFile (request).delete ();
        statusFile (request).delete ();
    }

    public static String asyncResponse (String request)
    {
        if (asyncStatus (request) != ASYNC_READY)
        {
            // We filter out any partial results
            return (null);
        }

        // Transaction successful, read response and cleanup
        String contents = get_contents (responseFile (request));
        remove_transaction (request);
        return (contents);
    }

    public static String asyncPeekResponse (String request)
    {
        return (get_contents (responseFile (request)));
    }

    public static String asyncError (String request)
    {
        String error = get_contents (statusFile (request));

        // Always remove the transaction...
        remove_transaction (request);
        return (error);
    }
}

// EOF
