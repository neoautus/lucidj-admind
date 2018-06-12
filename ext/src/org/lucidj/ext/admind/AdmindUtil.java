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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

public class AdmindUtil
{
    private final static String BASE_DIRECTORY = "jvm_admind_";

    private static String jvm_id;
    private static String tmp_dir;
    private static String root_admind_dir;
    private static String admind_dir;

    private static Thread cleanup_thread_hook = null;

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

        // TODO: HANDLE PERMISSIONS ON WINDOZE TOO

        // Set directory permissions as usable only to owner (u+rwx,go-rwx)
        Set<PosixFilePermission> perms = new HashSet<> ();
        perms.add (PosixFilePermission.OWNER_READ);
        perms.add (PosixFilePermission.OWNER_WRITE);
        perms.add (PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions (fdir.toPath (), perms);
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
                            FileOutputStream postmortem = new FileOutputStream (root_admind_dir + "/postmortem.log", true);
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

        Files.walkFileTree (Paths.get (admind_dir), new SimpleFileVisitor<Path> ()
        {
            @Override
            public FileVisitResult postVisitDirectory (Path dir, IOException exc)
                throws IOException
            {
                Files.delete (dir);
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
}

// EOF
