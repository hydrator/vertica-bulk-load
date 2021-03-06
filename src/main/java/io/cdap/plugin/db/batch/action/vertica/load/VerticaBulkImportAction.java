/*
 * Copyright © 2017 Cask Data, Inc.
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

package io.cdap.plugin.db.batch.action.vertica.load;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.vertica.jdbc.VerticaConnection;
import com.vertica.jdbc.VerticaCopyStream;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.cdap.etl.api.FailureCollector;
import io.cdap.cdap.etl.api.PipelineConfigurer;
import io.cdap.cdap.etl.api.StageConfigurer;
import io.cdap.cdap.etl.api.action.Action;
import io.cdap.cdap.etl.api.action.ActionContext;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a query after a pipeline run.
 */
@Plugin(type = Action.PLUGIN_TYPE)
@Name("VerticaBulkImportAction")
@Description("Vertica bulk load plugin")
public class VerticaBulkImportAction extends Action {
  private static final Logger LOG = LoggerFactory.getLogger(VerticaBulkImportAction.class);
  private final VerticaImportConfig config;

  public VerticaBulkImportAction(VerticaImportConfig config) {
    this.config = config;
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) throws IllegalArgumentException {
    super.configurePipeline(pipelineConfigurer);
    StageConfigurer stageConfigurer = pipelineConfigurer.getStageConfigurer();
    FailureCollector failureCollector = stageConfigurer.getFailureCollector();

    config.validate(failureCollector);
  }

  @Override
  public void run(ActionContext context) throws Exception {
    FailureCollector failureCollector = context.getFailureCollector();
    config.validate(failureCollector);
    failureCollector.getOrThrowException();

    Object driver = Class.forName("com.vertica.jdbc.Driver").newInstance();
    DriverManager.registerDriver((Driver) driver);

    Preconditions.checkArgument(
      tableExists(config.getTableName()),
      "Table %s does not exist. Please check that the 'tableName' property " +
        "has been set correctly, and that the connection string %s points to a valid database.",
      config.getTableName(), config.getConnectionString());

    String copyStatement;

    if (config.getLevel().equalsIgnoreCase("basic")) {
      // COPY tableName FROM STDIN DELIMITER 'delimiter'
      copyStatement = String.format("COPY %s FROM STDIN DELIMITER '%s'", config.getTableName(), config.getDelimiter());
    } else {
      copyStatement = config.getCopyStatement();
    }

    LOG.debug("Copy statement is: {}", copyStatement);

    try {
      try (Connection connection = DriverManager.getConnection(config.getConnectionString(), config.getUser(),
                                                               config.getPassword())) {
        connection.setAutoCommit(false);
        // run Copy statement
        VerticaCopyStream stream = new VerticaCopyStream((VerticaConnection) connection, copyStatement);
        // Keep running count of the number of rejects
        int totalRejects = 0;

        // start() starts the stream process, and opens the COPY command.
        stream.start();

        FileSystem fs = FileSystem.get(new Configuration());

        List<String> fileList = new ArrayList<>();
        FileStatus[] fileStatus;
        try {
          fileStatus = fs.listStatus(new Path(config.getPath()));
          for (FileStatus fileStat : fileStatus) {
            fileList.add(fileStat.getPath().toString());
          }
        } catch (FileNotFoundException e) {
          throw new IllegalArgumentException(String.format(
            String.format("Path %s not found on file system. Please provide correct path.", config.getPath()), e));
        }

        if (fileStatus.length <= 0) {
          LOG.warn("No files available to load into vertica database");
        }

        for (String file : fileList) {
          Path path = new Path(file);

          FSDataInputStream inputStream = fs.open(path);
          // Add stream to the VerticaCopyStream
          stream.addStream(inputStream);

          // call execute() to load the newly added stream. You could
          // add many streams and call execute once to load them all.
          // Which method you choose depends mainly on whether you want
          // the ability to check the number of rejections as the load
          // progresses so you can stop if the number of rejects gets too
          // high. Also, high numbers of InputStreams could create a
          // resource issue on your client system.
          stream.execute();

          // Show any rejects from this execution of the stream load
          // getRejects() returns a List containing the
          // row numbers of rejected rows.
          List<Long> rejects = stream.getRejects();

          // The size of the list gives you the number of rejected rows.
          int numRejects = rejects.size();
          totalRejects += numRejects;
          if (config.getAutoCommit().equalsIgnoreCase("true")) {
            // Commit the loaded data
            connection.commit();
          }
        }

        // Finish closes the COPY command. It returns the number of
        // rows inserted.
        long results = stream.finish();

        context.getMetrics().gauge("num.of.rows.rejected", totalRejects);
        context.getMetrics().gauge("num.of.rows.inserted", results);

        // Commit the loaded data
        connection.commit();
      }
    } catch (Exception e) {
      throw new RuntimeException(String.format("Exception while running copy statement %s", copyStatement), e);
    } finally {
      DriverManager.deregisterDriver((Driver) driver);
    }
  }

  public boolean tableExists(String tableName) {
    Connection connection;
    try {
      if (config.getUser() == null) {
        connection = DriverManager.getConnection(config.getConnectionString());
      } else {
        connection = DriverManager.getConnection(config.getConnectionString(), config.getUser(), config.getPassword());
      }

      try {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getTables(null, null, tableName, null)) {
          return rs.next();
        }
      } finally {
        connection.close();
      }
    } catch (SQLException e) {
      LOG.error("Exception while trying to check the existence of database table {} for connection {}.",
                tableName, config.getConnectionString(), e);
      throw Throwables.propagate(e);
    }
  }
}
