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

package org.lucidj.examples.invoke;

import org.lucidj.admind.shared.AdmindUtil;

public class Invoke
{
    public static void main (String[] args)
    {
        System.out.println ("-----");
        String def_server_name = AdmindUtil.getServerName ();
        System.out.println ("Default server name: " + def_server_name);

        String admind = AdmindUtil.initAdmindDir ();

        if (admind == null)
        {
            System.out.println ("Unable to find admind for " + def_server_name);
            System.exit (1);
        }

        System.out.println ("AdminD directory: " + admind);
        System.out.println ("-----");

        long start = System.nanoTime ();

        String data = "The quick brown fox jumped over the lazy dogs!";
        String request = AdmindUtil.asyncInvoke ("toupper", data);
        String response = null;
        String error = null;

        int status = AdmindUtil.asyncWait (request);

        if (status == AdmindUtil.ASYNC_READY)
        {
            response = AdmindUtil.asyncResponse (request);
        }
        else if (status == AdmindUtil.ASYNC_GONE)
        {
            error = "Server gone";
        }
        else
        {
            response = AdmindUtil.asyncPeekResponse (request);
            error = AdmindUtil.asyncError (request);
        }

        long finish = System.nanoTime ();

        System.out.println ("Request      : " + request);
        System.out.println ("Request data : " + data);
        System.out.println ("Response     : " + response);
        System.out.println ("Error        : " + error);
        System.out.println ("Running time : " + (finish - start) / 1000000.0 + "ms");
        System.out.println ("-----");
    }
}

// EOF
