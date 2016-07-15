package com.google.cloud.hadoop.io.bigquery;

import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.Bigquery.Jobs.Insert;
import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobConfiguration;
import com.google.api.services.bigquery.model.JobConfigurationExtract;
import com.google.api.services.bigquery.model.JobReference;
import com.google.api.services.bigquery.model.Table;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.cloud.hadoop.util.ApiErrorExtractor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.apache.hadoop.util.Progressable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper for BigQuery API.
 */
public class BigQueryHelper {
  // BigQuery job_ids must match this pattern.
  public static final String BIGQUERY_JOB_ID_PATTERN = "[a-zA-Z0-9_-]+";

  // Maximum number of characters in a BigQuery job_id.
  public static final int BIGQUERY_JOB_ID_MAX_LENGTH = 1024;

  // Logger.
  protected static final Logger LOG = LoggerFactory.getLogger(BigQueryHelper.class);

  // Used for specialized handling of various API-defined exceptions.
  private ApiErrorExtractor errorExtractor = new ApiErrorExtractor();

  private Bigquery service;

  public BigQueryHelper(Bigquery service) {
    this.service = service;
  }

  /**
   * Returns the underlying Bigquery instance used for communicating with the BigQuery API.
   */
  public Bigquery getRawBigquery() {
    return service;
  }

  /**
   * Exports BigQuery results into GCS, polls for completion before returning.
   *
   * @param projectId the project on whose behalf to perform the export.
   * @param tableRef the table to export.
   * @param gcsPaths the GCS paths to export to.
   * @param awaitCompletion if true, block and poll until job completes, otherwise return as soon as
   *        the job has been successfully dispatched.
   *
   * @throws IOException on IO error.
   * @throws InterruptedException on interrupt.
   */
  public void exportBigQueryToGcs(String projectId, TableReference tableRef, List<String> gcsPaths,
      boolean awaitCompletion) throws IOException, InterruptedException {
    LOG.debug("exportBigQueryToGcs(bigquery, '{}', '{}', '{}', '{}')", projectId,
        BigQueryStrings.toString(tableRef), gcsPaths, awaitCompletion);
    LOG.info("Exporting table '{}' to {} paths; path[0] is '{}'; awaitCompletion: {}",
        BigQueryStrings.toString(tableRef), gcsPaths.size(), gcsPaths.get(0), awaitCompletion);

    // Create job and configuration.
    JobConfigurationExtract extractConfig = new JobConfigurationExtract();

    // Set source.
    extractConfig.setSourceTable(tableRef);

    // Set destination.
    extractConfig.setDestinationUris(gcsPaths);
    extractConfig.set("destinationFormat", "NEWLINE_DELIMITED_JSON");

    JobConfiguration config = new JobConfiguration();
    config.setExtract(extractConfig);

    JobReference jobReference = createJobReference(projectId, "direct-bigqueryhelper-export");

    Job job = new Job();
    job.setConfiguration(config);
    job.setJobReference(jobReference);

    // Insert and run job.
    insertJobOrFetchDuplicate(projectId, job);

    // Create anonymous Progressable object
    Progressable progressable = new Progressable() {
      @Override
      public void progress() {
        // TODO(user): ensure task doesn't time out
      }
    };

    if (awaitCompletion) {
      // Poll until job is complete.
      BigQueryUtils.waitForJobCompletion(service, projectId, jobReference, progressable);
    }
  }

  /**
   * Returns true if the table exists, or false if not.
   */
  public boolean tableExists(TableReference tableRef) throws IOException {
    try {
      Table fetchedTable = service.tables().get(
          tableRef.getProjectId(), tableRef.getDatasetId(), tableRef.getTableId()).execute();
      LOG.debug("Successfully fetched table '{}' for tableRef '{}'", fetchedTable, tableRef);
      return true;
    } catch (IOException ioe) {
      if (errorExtractor.itemNotFound(ioe)) {
        return false;
      } else {
        // Unhandled exceptions should just propagate up.
        throw ioe;
      }
    }
  }

  /**
   * Gets the specified table resource by table ID. This method does not return the data in the
   * table, it only returns the table resource, which describes the structure of this table.
   *
   * @param tableRef The BigQuery table reference.
   * @return The table resource, which describes the structure of this table.
   * @throws IOException
   */
  public Table getTable(TableReference tableRef)
      throws IOException {
    Bigquery.Tables.Get getTablesReply = service.tables().get(
        tableRef.getProjectId(), tableRef.getDatasetId(), tableRef.getTableId());
    return getTablesReply.execute();
  }

  /**
   * Gets the schema of this table.
   *
   * @param tableRef The BigQuery table reference.
   * @return value or null for none
   * @throws IOException
   */
  public TableSchema getTableSchema(TableReference tableRef)
      throws IOException {
    Table table = getTable(tableRef);
    return table.getSchema();
  }

  /**
   * Creates a new JobReference with a unique jobId generated from {@code jobIdPrefix} plus a
   * randomly generated UUID String.
   */
  public JobReference createJobReference(String projectId, String jobIdPrefix) {
    Preconditions.checkArgument(projectId != null, "projectId must not be null.");
    Preconditions.checkArgument(jobIdPrefix != null, "jobIdPrefix must not be null.");
    Preconditions.checkArgument(jobIdPrefix.matches(BIGQUERY_JOB_ID_PATTERN),
        "jobIdPrefix '%s' must match pattern '%s'", jobIdPrefix, BIGQUERY_JOB_ID_PATTERN);

    String fullJobId = String.format("%s-%s", jobIdPrefix, UUID.randomUUID().toString());
    Preconditions.checkArgument(fullJobId.length() <= BIGQUERY_JOB_ID_MAX_LENGTH,
        "fullJobId '%s' has length '%s'; must be less than or equal to %s",
        fullJobId, fullJobId.length(), BIGQUERY_JOB_ID_MAX_LENGTH);
    return new JobReference()
        .setProjectId(projectId)
        .setJobId(fullJobId);
  }

  /**
   * Helper to check for non-null Job.getJobReference().getJobId() and quality of the getJobId()
   * between {@code expected} and {@code actual}, using Preconditions.checkState.
   */
  public void checkJobIdEquality(Job expected, Job actual) {
    Preconditions.checkState(actual.getJobReference() != null
        && actual.getJobReference().getJobId() != null
        && expected.getJobReference() != null
        && expected.getJobReference().getJobId() != null
        && actual.getJobReference().getJobId().equals(expected.getJobReference().getJobId()),
        "jobIds must match in '[expected|actual].getJobReference()' (got '%s' vs '%s')",
        expected.getJobReference(), actual.getJobReference());
  }

  /**
   * Tries to run jobs().insert(...) with the provided {@code projectId} and {@code job}, which
   * returns a {@code Job} under normal operation, which is then returned from this method.
   * In case of an exception being thrown, if the cause was "409 conflict", then we issue a
   * separate "jobs().get(...)" request and return the results of that fetch instead.
   * Other exceptions propagate out as normal.
   */
  public Job insertJobOrFetchDuplicate(String projectId, Job job) throws IOException {
    Preconditions.checkArgument(
        job.getJobReference() != null && job.getJobReference().getJobId() != null,
        "Require non-null JobReference and JobId inside; getJobReference() == '%s'",
        job.getJobReference());
    Insert insert = service.jobs().insert(projectId, job);
    Job response = null;
    try {
      response = insert.execute();
      LOG.debug("Successfully inserted job '{}'. Response: '{}'", job, response);
    } catch (IOException ioe) {
      if (errorExtractor.itemAlreadyExists(ioe)) {
        LOG.info(String.format(
            "Fetching existing job after catching exception for duplicate jobId '%s'",
            job.getJobReference().getJobId()), ioe);
        response = service.jobs().get(projectId, job.getJobReference().getJobId()).execute();
      } else {
        LOG.info(String.format(
            "Unhandled exception trying to insert job '%s'", job), ioe);
        throw ioe;
      }
    }
    checkJobIdEquality(job, response);
    return response;
  }

  @VisibleForTesting
  void setErrorExtractor(ApiErrorExtractor errorExtractor) {
    this.errorExtractor = errorExtractor;
  }
}
