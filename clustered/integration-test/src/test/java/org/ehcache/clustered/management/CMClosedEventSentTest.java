/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ehcache.clustered.management;

import org.ehcache.CacheManager;
import org.ehcache.Status;
import org.ehcache.config.units.EntryUnit;
import org.ehcache.config.units.MemoryUnit;
import org.ehcache.management.registry.DefaultManagementRegistryConfiguration;
import org.junit.ClassRule;
import org.junit.Test;
import org.terracotta.management.model.message.Message;
import org.terracotta.management.model.notification.ContextualNotification;
import org.terracotta.testing.rules.Cluster;

import java.io.File;

import static org.ehcache.clustered.client.config.builders.ClusteredResourcePoolBuilder.clusteredDedicated;
import static org.ehcache.clustered.client.config.builders.ClusteringServiceConfigurationBuilder.cluster;
import static org.ehcache.config.builders.CacheConfigurationBuilder.newCacheConfigurationBuilder;
import static org.ehcache.config.builders.CacheManagerBuilder.newCacheManagerBuilder;
import static org.ehcache.config.builders.ResourcePoolsBuilder.newResourcePoolsBuilder;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.terracotta.testing.rules.BasicExternalClusterBuilder.newCluster;

public class CMClosedEventSentTest {

  private static final String RESOURCE_CONFIG =
    "<config xmlns:ohr='http://www.terracotta.org/config/offheap-resource'>"
      + "<ohr:offheap-resources>"
      + "<ohr:resource name=\"primary-server-resource\" unit=\"MB\">64</ohr:resource>"
      + "<ohr:resource name=\"secondary-server-resource\" unit=\"MB\">64</ohr:resource>"
      + "</ohr:offheap-resources>"
      + "</config>\n"
      + "<service xmlns:lease='http://www.terracotta.org/service/lease'>"
      + "<lease:connection-leasing>"
      + "<lease:lease-length unit='seconds'>5</lease:lease-length>"
      + "</lease:connection-leasing>"
      + "</service>";

  @ClassRule
  public static Cluster CLUSTER = newCluster().in(new File("build/cluster")).withServiceFragment(RESOURCE_CONFIG).build();

  @Test(timeout = 60_000)
  public void test_CACHE_MANAGER_CLOSED() throws Exception {
    AbstractClusteringManagementTest.createNmsService(CLUSTER);

    CacheManager cacheManager = newCacheManagerBuilder()
      // cluster config
      .with(cluster(CLUSTER.getConnectionURI().resolve("/my-server-entity-1"))
        .autoCreate()
        .defaultServerResource("primary-server-resource")
        .resourcePool("resource-pool-a", 10, MemoryUnit.MB, "secondary-server-resource") // <2>
        .resourcePool("resource-pool-b", 10, MemoryUnit.MB)) // will take from primary-server-resource
      // management config
      .using(new DefaultManagementRegistryConfiguration()
        .addTags("webapp-1", "server-node-1")
        .setCacheManagerAlias("my-super-cache-manager"))
      // cache config
      .withCache("dedicated-cache-1", newCacheConfigurationBuilder(
        String.class, String.class,
        newResourcePoolsBuilder()
          .heap(10, EntryUnit.ENTRIES)
          .offheap(1, MemoryUnit.MB)
          .with(clusteredDedicated("primary-server-resource", 4, MemoryUnit.MB)))
        .build())
      .build(true);

    assertThat(cacheManager.getStatus(), equalTo(Status.AVAILABLE));
    waitFor("CACHE_MANAGER_AVAILABLE");

    cacheManager.close();
    waitFor("CACHE_MANAGER_CLOSED");
  }

  private void waitFor(String notifType) throws InterruptedException {
    while (!Thread.currentThread().isInterrupted()) {
      Message message = AbstractClusteringManagementTest.nmsService.waitForMessage();
      if (message.getType().equals("NOTIFICATION")) {
        ContextualNotification notification = message.unwrap(ContextualNotification.class).get(0);
        if (notification.getType().equals(notifType)) {
          break;
        }
      }
    }
    assertFalse(Thread.currentThread().isInterrupted());
  }

}
