/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.beam.runners.spark.translation;

import org.apache.beam.runners.spark.SparkContextOptions;
import org.apache.beam.runners.spark.SparkPipelineOptions;
import org.apache.beam.runners.spark.coders.BeamSparkRunnerRegistrator;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.serializer.KryoSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Spark context factory.
 */
public final class SparkContextFactory {
  private static final Logger LOG = LoggerFactory.getLogger(SparkContextFactory.class);

  /**
   * If the property {@code beam.spark.test.reuseSparkContext} is set to
   * {@code true} then the Spark context will be reused for beam pipelines.
   * This property should only be enabled for tests.
   */
  static final String TEST_REUSE_SPARK_CONTEXT = "beam.spark.test.reuseSparkContext";

  private static JavaSparkContext sparkContext;
  private static String sparkMaster;

  private SparkContextFactory() {
  }

  public static synchronized JavaSparkContext getSparkContext(SparkPipelineOptions options) {
    SparkContextOptions contextOptions = options.as(SparkContextOptions.class);
    // reuse should be ignored if the context is provided.
    if (Boolean.getBoolean(TEST_REUSE_SPARK_CONTEXT)
        && !contextOptions.getUsesProvidedSparkContext()) {
      // if the context is null or stopped for some reason, re-create it.
      if (sparkContext == null || sparkContext.sc().isStopped()) {
        sparkContext = createSparkContext(contextOptions);
        sparkMaster = options.getSparkMaster();
      } else if (!options.getSparkMaster().equals(sparkMaster)) {
        throw new IllegalArgumentException(String.format("Cannot reuse spark context "
            + "with different spark master URL. Existing: %s, requested: %s.",
                sparkMaster, options.getSparkMaster()));
      }
      return sparkContext;
    } else {
      return createSparkContext(contextOptions);
    }
  }

  static synchronized void stopSparkContext(JavaSparkContext context) {
    if (!Boolean.getBoolean(TEST_REUSE_SPARK_CONTEXT)) {
      context.stop();
    }
  }

  private static JavaSparkContext createSparkContext(SparkContextOptions contextOptions) {
    if (contextOptions.getUsesProvidedSparkContext()) {
      LOG.info("Using a provided Spark Context");
      JavaSparkContext jsc = contextOptions.getProvidedSparkContext();
      if (jsc == null || jsc.sc().isStopped()){
        LOG.error("The provided Spark context " + jsc + " was not created or was stopped");
        throw new RuntimeException("The provided Spark context was not created or was stopped");
      }
      return jsc;
    } else {
      LOG.info("Creating a brand new Spark Context.");
      SparkConf conf = new SparkConf();
      if (!conf.contains("spark.master")) {
        // set master if not set.
        conf.setMaster(contextOptions.getSparkMaster());
      }
      conf.setAppName(contextOptions.getAppName());
      // register immutable collections serializers because the SDK uses them.
      conf.set("spark.kryo.registrator", BeamSparkRunnerRegistrator.class.getName());
      conf.set("spark.serializer", KryoSerializer.class.getName());
      return new JavaSparkContext(conf);
    }
  }
}
