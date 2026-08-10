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

package org.apache.livy.rsc.driver;

import java.util.concurrent.TimeUnit;

import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.hive.HiveContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SparkEntries {

  private static final Logger LOG = LoggerFactory.getLogger(SparkEntries.class);

  private volatile JavaSparkContext sc;
  private final SparkConf conf;
  private volatile SQLContext sqlctx;
  private volatile HiveContext hivectx;
  private volatile SparkSession sparksession;

  public SparkEntries(SparkConf conf) {
    this.conf = conf;
  }

  public JavaSparkContext sc() {
    if (sc == null) {
      synchronized (this) {
        if (sc == null) {
          long t1 = System.nanoTime();
          LOG.info("Starting Spark context...");
          SparkContext scalaSc = SparkContext.getOrCreate(conf);
          sc = new JavaSparkContext(scalaSc);
          LOG.info("Spark context finished initialization in {}ms",
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1));
        }
      }
    }
    return sc;
  }

  public SparkSession sparkSession() {
    if (sparksession == null) {
      synchronized (this) {
        if (sparksession == null) {
          SparkSession.Builder builder = SparkSession.builder().sparkContext(sc().sc());
          try {
            SparkConf conf = sc().getConf();
            String catalog = conf.get("spark.sql.catalogImplementation", "in-memory").toLowerCase();

            if (catalog.equals("hive") && hiveClassesArePresent()) {
              ClassLoader loader = Thread.currentThread().getContextClassLoader() != null ?
                Thread.currentThread().getContextClassLoader() : getClass().getClassLoader();
              if (loader.getResource("hive-site.xml") == null) {
                LOG.warn("livy.repl.enable-hive-context is true but no hive-site.xml found on " +
                 "classpath");
              }

              builder.enableHiveSupport();
              sparksession = builder.getOrCreate();
              LOG.info("Created Spark session (with Hive support).");
            } else {
              builder.config("spark.sql.catalogImplementation", "in-memory");
              sparksession = builder.getOrCreate();
              LOG.info("Created Spark session.");
            }
          } catch (Exception e) {
            LOG.warn("SparkSession is not supported", e);
            throw e;
          }
        }
      }
    }

    return sparksession;
  }

  public SQLContext sqlctx() {
    if (sqlctx == null) {
      synchronized (this) {
        if (sqlctx == null) {
          sqlctx = sparkSession().sqlContext();
          LOG.info("Created SQLContext.");
        }
      }
    }
    return sqlctx;
  }

  public HiveContext hivectx() {
    if (hivectx == null) {
      synchronized (this) {
        if (hivectx == null) {
          SparkConf conf = sc().getConf();
          if (conf.getBoolean("spark.repl.enableHiveContext", false) ||
            conf.get("spark.sql.catalogImplementation", "in-memory").toLowerCase()
              .equals("hive")) {
            ClassLoader loader = Thread.currentThread().getContextClassLoader() != null ?
              Thread.currentThread().getContextClassLoader() : getClass().getClassLoader();
            if (loader.getResource("hive-site.xml") == null) {
              LOG.warn("livy.repl.enable-hive-context is true but no hive-site.xml found on " +
               "classpath.");
            }
            hivectx = new HiveContext(sc().sc());
            LOG.info("Created HiveContext.");
          }
        }
      }
    }
    return hivectx;
  }

  public synchronized void stop() {
    if (sc != null) {
      sc.stop();
    }
  }

  /**
   * Determine whether Spark's Hive support classes are present on the classpath.
   *
   * <p>Spark 3 exposed this as {@code org.apache.spark.sql.SparkSession$.hiveClassesArePresent()}.
   * In Spark 4 {@code SparkSession} became abstract and the concrete companion moved to
   * {@code org.apache.spark.sql.classic.SparkSession$}. We probe the Spark 4 location first
   * because on a Spark 4 classpath the old {@code SparkSession$} may still resolve as a stub
   * that no longer carries the concrete Hive check; the classic companion is the source of
   * truth. On Spark 3 the classic class is absent and we fall through to the legacy companion.
   */
  private static boolean hiveClassesArePresent() {
    String[] candidates = {
        "org.apache.spark.sql.classic.SparkSession$", // Spark 4+
        "org.apache.spark.sql.SparkSession$"          // Spark 3
    };
    for (String cls : candidates) {
      try {
        Class<?> companion = Class.forName(cls);
        Object module = companion.getField("MODULE$").get(null);
        return (Boolean) companion.getMethod("hiveClassesArePresent").invoke(module);
      } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException e) {
        // Try the next candidate.
      } catch (ReflectiveOperationException e) {
        LOG.warn("Failed to invoke {}.hiveClassesArePresent()", cls, e);
        return false;
      }
    }
    LOG.warn("Could not locate SparkSession#hiveClassesArePresent on the classpath");
    return false;
  }
}
