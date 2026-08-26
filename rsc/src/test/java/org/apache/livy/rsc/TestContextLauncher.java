/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.livy.rsc;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

import static org.apache.livy.rsc.RSCConf.Entry.DRIVER_ADDRESS_USE_HOSTNAME;

public class TestContextLauncher {

  @Test
  public void testResolveDriverAddressUsesSocketIpByDefault() throws Exception {
    RSCConf conf = new RSCConf(null);
    BaseProtocol.RemoteDriverAddress msg =
      new BaseProtocol.RemoteDriverAddress("driver.example.internal", 1234);
    InetSocketAddress remote =
      new InetSocketAddress(InetAddress.getLoopbackAddress(), 5678);

    String resolved = ContextLauncher.resolveDriverAddress(conf, msg, remote);

    assertEquals(remote.getAddress().getHostAddress(), resolved);
  }

  @Test
  public void testResolveDriverAddressUsesHostnameWhenEnabled() throws Exception {
    RSCConf conf = new RSCConf(null);
    conf.set(DRIVER_ADDRESS_USE_HOSTNAME, true);
    BaseProtocol.RemoteDriverAddress msg =
      new BaseProtocol.RemoteDriverAddress("driver.example.internal", 1234);
    InetSocketAddress remote =
      new InetSocketAddress(InetAddress.getLoopbackAddress(), 5678);

    String resolved = ContextLauncher.resolveDriverAddress(conf, msg, remote);

    assertEquals("driver.example.internal", resolved);
  }
}
