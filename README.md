# Apache Flink® Table API on Confluent Cloud - Examples

This repository contains examples for running Apache Flink's Table API on Confluent Cloud.

## Introduction to Table API for Java

The [Table API](https://nightlies.apache.org/flink/flink-docs-stable/docs/dev/table/overview/) enables a programmatic
way of developing, testing, and submitting Flink pipelines for processing data streams.
Streams can be finite or infinite, with insert-only or changelog data. The latter allows for dealing with *Change Data
Capture* (CDC) events.

Within the API, you conceptually work with tables that change over time - inspired by relational databases. Write
a *Table Program* as a declarative and structured graph of data transformations. Table API is inspired by SQL and complements
it with additional tools for juggling real-time data. You can mix and match Flink SQL with Table API at any time as they
go hand in hand.

## Table API on Confluent Cloud

Table API on Confluent Cloud is a client-side library that delegates Flink API calls to Confluent’s public
REST API. It submits [Statements](https://docs.confluent.io/cloud/current/api.html#tag/Statements-(sqlv1)) and retrieves
[StatementResults](https://docs.confluent.io/cloud/current/api.html#tag/Statement-Results-(sqlv1)).

Table programs are implemented against [Flink's open source Table API for Java](https://github.com/apache/flink/tree/master/flink-table/flink-table-api-java).
The provided Confluent plugin injects Confluent-specific components for powering the `TableEnvironment` without the need
for a local Flink cluster. By adding the `confluent-flink-table-api-java-plugin` dependency, Flink internal components such as
`CatalogStore`, `Catalog`, `Planner`, `Executor`, and configuration are managed by the plugin and fully integrate with
Confluent Cloud. Including access to Apache Kafka®, Schema Registry, and Flink Compute Pools.

Note: The Table API plugin is in Open Preview stage. Take a look at the [Known Limitation](#known-limitations) section below.

### Motivating Example

The following code shows how a Table API program is structured. Subsequent sections will go into more details how you
can use the examples of this repository to play around with Flink on Confluent Cloud.

```java
import io.confluent.flink.plugin.*;
import org.apache.flink.table.api.*;
import org.apache.flink.types.Row;
import static org.apache.flink.table.api.Expressions.*;
import java.util.List;

// A table program...
//   - runs in a regular main() method
//   - uses Apache Flink's APIs
//   - communicates to Confluent Cloud via REST calls
public static void main(String[] args) {
  // Set up the connection to Confluent Cloud
  EnvironmentSettings settings = ConfluentSettings.fromResource("/cloud.properties");
  TableEnvironment env = TableEnvironment.create(settings);

  // Run your first Flink statement in Table API
  env.fromValues(row("Hello world!")).execute().print();

  // Or use SQL
  env.sqlQuery("SELECT 'Hello world!'").execute().print();

  // Structure your code with Table objects - the main ingredient of Table API.
  Table table =
      env.from("examples.marketplace.clicks").filter($("user_agent").like("Mozilla%"));

  table.printSchema();
  table.printExplain();

  // Use the provided tools to test on a subset of the streaming data
  List<Row> expected = ConfluentTools.collectMaterialized(table, 50);
  List<Row> actual = List.of(Row.of(42, 500));
  if (!expected.equals(actual)) {
    // Print all data
    System.out.println("Results don't match");
    System.out.println(
        expected.stream().map(Row::toString).collect(Collectors.joining("\n")));
    // Or access nested data
    System.out.println("First row: " + expected.get(0).getFieldAs("user_id"));
  }

  // Access your Kafka topics or start with the built-in examples
  // with unbounded data sets
  env.from("examples.marketplace.clicks")
      .groupBy($("user_id"))
      .select($("user_id"), $("view_time").sum())
      .execute()
      .print();

  // Or pipe data from A to B
  TablePipeline pipeline = env.from("A").select(withAllColumns()).insertInto("B");
  // Asynchronously
  // pipeline.execute();
  // Or synchronously
  // pipeline.execute().await();
```

## Developer Journey

The examples in this repository follow the journey of taking a table program from a first
experiment all the way to a production deployment:

| Stage | What you do | Where to look |
|-------|-------------|---------------|
| Get started | Configure a connection to Confluent Cloud and run a first program | `Example_00` - `Example_02`, [Getting Started](#getting-started) |
| Build | Transform tables, build pipelines, work with data types, UDFs, and structured objects | `Example_03` - `Example_07`, `Example_09` - `Example_11`, `TableProgramTemplate` |
| Test locally | Run unit tests on mock data, without Confluent Cloud connectivity | `src/test/java/`, [Testing Table Programs](#testing-table-programs) |
| Test on Confluent Cloud | Run integration tests against the real service | `Example_08_IntegrationAndDeploymentIT`, [Testing Table Programs](#testing-table-programs) |
| Deploy | Submit statements with deterministic names from a CI/CD pipeline | `Example_08_IntegrationAndDeployment`, [CI/CD with GitHub Actions](#cicd-with-github-actions) |
| Operate | List, describe, stop, resume, and delete deployed statements | `.github/workflows-examples/manage.yml` |

## Getting Started

### Prerequisites

1. Sign up for Confluent Cloud at [https://confluent.cloud](https://confluent.cloud/signup)
2. [Create a compute pool](https://docs.confluent.io/cloud/current/flink/operate-and-deploy/create-compute-pool.html#create-a-compute-pool-in-ccloud-console)
   in the web UI of Confluent's Cloud Console
3. [Generate an API Key](https://docs.confluent.io/cloud/current/flink/operate-and-deploy/generate-api-key-for-flink.html#generate-an-api-key)
   for the region where you created your compute pool
4. Optional: [Create a Kafka cluster](https://docs.confluent.io/cloud/current/clusters/create-cluster.html#manage-ak-clusters-on-ccloud)
   if you want to run examples that store data in Kafka

### Run Examples

Examples are runnable from the command-line or an IDE. Command-line is convenient for CI/CD integration. IDE is
recommended for development, debugging, and playing around in an interactive manner.

All example files are located in `src/main/java/io/confluent/flink/examples/table`. Each file contains a Java `main()`
method with a table program that can be executed individually. Every example program covers a different topic to learn
more about how Table API can be used. It is recommended to go through the examples in the defined order as they partially
build on top of each other.

Clone this repository to your local computer, or download it as a ZIP file and extract it.
```bash
git clone https://github.com/confluentinc/flink-table-api-java-examples.git
```

#### Via Command-Line

Change the current directory.
```bash
cd flink-table-api-java-examples
```

Use Maven to build a JAR file of the project. Make sure you have at least Java 11 installed.
The included Maven wrapper `mvnw` is useful for a consistent Maven version, you don't need to install Maven.
```bash
./mvnw clean package
```

Run an example from the JAR file. No worries the program is read-only, so it won't affect your existing
Kafka clusters. All results will be printed to the console.
```bash
cd target
java -cp flink-table-api-java-examples-1.0.jar io.confluent.flink.examples.table.Example_00_HelloWorld
```

An output similar to the following means that you are able to run the examples:
```text
Exception in thread "main" io.confluent.flink.plugin.ConfluentFlinkException: Parameter 'client.organization-id' not found.
```
Configuration will be covered in the next section.

#### Via IDE

Import this repository into your IDE (preferably [IntelliJ IDEA](https://www.jetbrains.com/idea/)). Make sure to select
the `pom.xml` file during import to treat it as a Maven project, this ensures that all dependencies will be loaded
automatically.

All examples are runnable from within the IDE. You simply need to execute the `main()` method of any example class.
Take a look at the `Example_00_HelloWorld` class to get started.

Run the `main()` method of `Example_00_HelloWorld`. No worries the program is read only so it won't affect your existing
Kafka clusters. All results will be printed to the console.

An output similar to the following means that you are able to run the examples:
```text
Exception in thread "main" io.confluent.flink.plugin.ConfluentFlinkException: Parameter 'client.organization-id' not found.
```
Configuration will be covered in the next section.

### Configure the `cloud.properties` File

The Table API plugin needs a set of configuration options for establishing a connection to Confluent Cloud.

For experimenting with Table API, configuration with a properties file might be the most convenient option.
The examples read from this file by default.

Update the file under `src/main/resources/cloud.properties` with your Confluent Cloud information.

All required information can be found in the web UI of Confluent's Cloud Console:
- `client.organization-id` from [**Menu** → **Settings** → **Organizations**](https://confluent.cloud/settings/organizations)
- `client.environment-id` from [**Menu** → **Environments**](https://confluent.cloud/environments)
- `client.cloud`, `client.region`, `client.compute-pool-id` from [**Menu** → **Environments**](https://confluent.cloud/environments) → **your environment** → **Flink** → **your compute pool**
- `client.flink-api-key`, `client.flink-api-secret` from [**Menu** → **Settings** → **API keys**](https://confluent.cloud/settings/api-keys)

Examples should be runnable after setting all configuration options correctly.

### Table API Playground using JShell

For convenience, the repository also contains a [JShell](https://openjdk.org/jeps/222) init script for playing around with
Table API in an interactive manner.

1. Switch into the `flink-table-api-java-examples` directory.

2. Run `mvn clean package` to build a JAR file.

3. Point to the `cloud.properties` file: `export FLINK_PROPERTIES=./src/main/resources/cloud.properties`

4. Start the shell with `jshell --class-path ./target/flink-table-api-java-examples-1.0.jar --startup ./jshell-init.jsh`

5. The `TableEnvironment` is pre-initialized from environment variables and available under `env`.

6. Run your first "Hello world!" using `env.executeSql("SELECT 'Hello world!'").print();`

### How to Continue

This repository can be used as a template for your own project and how to handle Maven dependencies correctly.

If you want to add the Table API to an existing project, make sure to include the following dependencies in the `<dependencies>`
section of your `pom.xml` file.

```xml
<!-- Apache Flink dependencies -->
<dependency>
  <groupId>org.apache.flink</groupId>
  <artifactId>flink-table-api-java</artifactId>
  <version>${flink.version}</version>
</dependency>

  <!-- Confluent Flink Table API Java plugin -->
<dependency>
<groupId>io.confluent.flink</groupId>
<artifactId>confluent-flink-table-api-java-plugin</artifactId>
<version>${confluent-plugin.version}</version>
</dependency>
```

The next section provides further details about how to handle configuration in production.

## Configuration

The Table API plugin needs a set of configuration options for establishing a connection to Confluent Cloud.

The `ConfluentSettings` class is a utility for providing configuration options from various sources.

For production, external input, code, and environment variables can be combined.

Precedence order (highest to lowest):
1. CLI Arguments or Properties File
2. Code
3. Environment Variables

A multi-layered configuration can look like:
```java
public static void main(String[] args) {
  // Args might set cloud, region, org, env, and compute pool.
  // Environment variables might pass key and secret.

  // Code sets the session name and SQL-specific options.
  ConfluentSettings settings = ConfluentSettings.newBuilder(args)
      .setContextName("MyTableProgram")
      .setOption("sql.local-time-zone", "UTC")
      .build();

  TableEnvironment env = TableEnvironment.create(settings);
}
```

### Via Properties File

Store options (or some options) in a `cloud.properties` file:

```properties
# Cloud region
client.cloud=aws
client.region=us-east-1

# Access & compute resources
client.flink-api-key=key
client.flink-api-secret=secret
client.organization-id=b0b21724-4586-4a07-b787-d0bb5aacbf87
client.environment-id=env-z3y2x1
client.compute-pool-id=lfcp-8m03rm
```

Reference the `cloud.properties` file:
```java
// Arbitrary file location in file system
ConfluentSettings settings = ConfluentSettings.fromFile("/path/to/cloud.properties");

// Part of the JAR package (in src/main/resources)
ConfluentSettings settings = ConfluentSettings.fromResource("/cloud.properties");
```

A path to a properties file can also be specified by setting the environment variable `FLINK_PROPERTIES`.

### Via Command-Line arguments

Pass all options (or some options) via command-line arguments:

```bash
java -jar my-table-program.jar \
  --cloud aws \
  --region us-east-1 \
  --flink-api-key key \
  --flink-api-secret secret \
  --organization-id b0b21724-4586-4a07-b787-d0bb5aacbf87 \
  --environment-id env-z3y2x1 \
  --compute-pool-id lfcp-8m03rm
```

In code call:

```java
public static void main(String[] args) {
  ConfluentSettings settings = ConfluentSettings.fromArgs(args);
}
```

### Via Code

Pass all options (or some options) in code:

```java
ConfluentSettings settings = ConfluentSettings.newBuilder()
    .setCloud("aws")
    .setRegion("us-east-1")
    .setFlinkApiKey("key")
    .setFlinkApiSecret("secret")
    .setOrganizationId("b0b21724-4586-4a07-b787-d0bb5aacbf87")
    .setEnvironmentId("env-z3y2x1")
    .setComputePoolId("lfcp-8m03rm")
    .build();
```

### Via Environment Variables

Pass all options (or some options) as variables:

```bash
export CLOUD_PROVIDER="aws"
export CLOUD_REGION="us-east-1"
export FLINK_API_KEY="key"
export FLINK_API_SECRET="secret"
export ORG_ID="b0b21724-4586-4a07-b787-d0bb5aacbf87"
export ENV_ID="env-z3y2x1"
export COMPUTE_POOL_ID="lfcp-8m03rm"

java -jar my-table-program.jar
```

In code call:
```java
ConfluentSettings settings = ConfluentSettings.fromGlobalVariables();
```

A path to a properties file can also be specified by setting the environment variable `FLINK_PROPERTIES`.

### Configuration Options

The following configuration needs to be provided:

| Property key              | CLI arg              | Environment variable | Required | Comment                                                                      |
|---------------------------|----------------------|----------------------|----------|------------------------------------------------------------------------------|
| `client.cloud`            | `--cloud`            | `CLOUD_PROVIDER`     | Y        | Confluent identifier for a cloud provider. For example: `aws`                |
| `client.region`           | `--region`           | `CLOUD_REGION`       | Y        | Confluent identifier for a cloud provider's region. For example: `us-east-1` |
| `client.flink-api-key`    | `--flink-api-key`    | `FLINK_API_KEY`      | Y        | API key for Flink access.                                                    |
| `client.flink-api-secret` | `--flink-api-secret` | `FLINK_API_SECRET`   | Y        | API secret for Flink access.                                                 |
| `client.organization-id`  | `--organization-id`  | `ORG_ID`             | Y        | ID of the organization. For example: `b0b21724-4586-4a07-b787-d0bb5aacbf87`  |
| `client.environment-id`   | `--environment-id`   | `ENV_ID`             | Y        | ID of the environment. For example: `env-z3y2x1`                             |
| `client.compute-pool-id`  | `--compute-pool-id`  | `COMPUTE_POOL_ID`    | Y        | ID of the compute pool. For example: `lfcp-8m03rm`                           |

Required configuration for supporting UDF uploads:

Note: Artifact key and secret can be created via Web Console under `API keys` -> `Cloud resource management`.

| Property key                 | CLI arg                 | Environment variable   | Comment                           |
|------------------------------|-------------------------|------------------------|-----------------------------------|
| `client.artifact-api-key`    | `--artifact-api-key`    | `ARTIFACT_API_KEY`     | API key for Artifact creation.    |
| `client.artifact-api-secret` | `--artifact-api-secret` | `ARTIFACT_API_SECRET`  | API secret for Artifact creation. |

Additional configuration:

| Property key                        | CLI arg                        | Environment variable         | Required | Comment                                                                                                                        |
|-------------------------------------|--------------------------------|------------------------------|----------|--------------------------------------------------------------------------------------------------------------------------------|
| `client.endpoint-template`          | `--endpoint-template`          | `ENDPOINT_TEMPLATE`          | N        | A template for the endpoint URL. For example: `https://flinkpls-dom123.{region}.{cloud}.confluent.cloud`                       |
| `client.artifact-endpoint-template` | `--artifact-endpoint-template` | `ARTIFACT_ENDPOINT_TEMPLATE` | N        | A template for the artifact endpoint URL. For example: `https://api.{region}.{cloud}.confluent.cloud`                          |
| `client.principal-id`               | `--principal-id`               | `PRINCIPAL_ID`               | N        | Principal that runs submitted statements. For example: `sa-23kgz4` (for a service account)                                     |
| `client.context`                    | `--context`                    |                              | N        | A name for this Table API session. For example: `my_table_program`                                                             |
| `client.statement-name`             | `--statement-name`             |                              | N        | Unique name for statement submission. By default, generated using a UUID.                                                      |
| `client.rest-endpoint`              | `--rest-endpoint`              | `REST_ENDPOINT`              | N        | URL to the REST endpoint. For example: `proxyto.confluent.cloud`                                                               |
| `client.catalog-cache`              |                                |                              | N        | Expiration time for catalog objects. For example: '5 min'. '1 min' by default. '0' disables the caching.                       |
| `client.tmp-dir`                    | `--tmp-dir`                    |                              | N        | Directory for temporary files created by the plugin, e.g. UDF jars. For example: '/tmp'. By default value of 'java.io.tmpdir'. |

### Endpoint Configuration

The Confluent Flink plugin provides options to configure endpoints for connecting to Confluent Cloud services. **The template-based approach is the recommended method.**

### `client.endpoint-template`

This option provides a template for constructing the Flink statement API endpoint URL.

- **Default**: `https://flink.{region}.{cloud}.confluent.cloud`
- **Example**: `https://flinkpls-dom123.{region}.{cloud}.confluent.cloud`
- **Usage**: The template supports placeholders `{region}` and `{cloud}` that are replaced with the configured region and cloud provider values.
- **Environment Variable**: `ENDPOINT_TEMPLATE`

### `client.artifact-endpoint-template`

This option provides a template for constructing the URL used for uploading artifacts (like UDF JARs).

- **Default**: `https://api.confluent.cloud`
- **Example**: `https://api.{region}.{cloud}.confluent.cloud`
- **Usage**: Similar to the endpoint template, this supports placeholders `{region}` and `{cloud}`.
- **Environment Variable**: `ARTIFACT_ENDPOINT_TEMPLATE`

### `client.rest-endpoint` (Discouraged)

This option specifies the base domain for REST API calls to Confluent Cloud. While still supported, using the template-based configuration above is preferred.

- **Default**: No default value
- **Example**: `proxy.confluent.cloud`
- **Usage**: When specified, the plugin constructs the full Flink statement API endpoint URL as `https://flink.{region}.{cloud}.{rest-endpoint}` where `{region}` and `{cloud}` are replaced with the configured region and cloud provider values.
- **Important**: `client.endpoint-template` and `client.rest-endpoint` are mutually exclusive. If both are set, an exception is thrown.
- **Environment Variable**: `REST_ENDPOINT`

### Relationship and Default Behavior

1. **Mutual Exclusivity**:
    - `client.endpoint-template` and `client.rest-endpoint` cannot be set simultaneously
    - `client.artifact-endpoint-template` and `client.rest-endpoint` cannot be set simultaneously

2. **Default Behavior**:
    - If neither `client.rest-endpoint` nor `client.endpoint-template` is configured, the default template `https://flink.{region}.{cloud}.confluent.cloud` is used for statement API
    - If neither `client.rest-endpoint` nor `client.artifact-endpoint-template` is specified, the default artifact endpoint `https://api.confluent.cloud` is used
    - If endpoint templates are used, each endpoint is constructed independently with the provided templates

### Examples

Here's a simple example showing different ways to configure endpoints:

```java
// Option 1 (RECOMMENDED): Using endpoint templates
// Resolved endpoints:
// - Statement API: https://flinkpls-dom123.us-east-1.aws.confluent.cloud
ConfluentSettings settings1 = ConfluentSettings.newBuilder()
        .setRegion("us-east-1")
        .setCloud("aws")
        .setEndpointTemplate("https://flinkpls-dom123.{region}.{cloud}.confluent.cloud")
        .setArtifactEndpointTemplate("https://artifacts.{region}.{cloud}.custom-domain.com")
        // Other required settings...
        .build();

// Option 2: Using properties file with endpoint templates
// cloud.properties:
// client.region=us-east-1
// client.cloud=aws
// client.endpoint-template=https://flinkpls-dom123.{region}.{cloud}.confluent.cloud
// Resolved endpoints:
// - Statement API: https://flinkpls-dom123.us-east-1.aws.confluent.cloud
// - Artifact API: https://api.confluent.cloud (default)
ConfluentSettings settings2 = ConfluentSettings.fromResource("/cloud.properties");

// Option 3 (DISCOURAGED): Using rest-endpoint (both statement endpoint will be derived from this)
// Resolved endpoints:
// - Statement API: https://flink.us-east-1.aws.proxy.confluent.cloud
// - Artifact API: https://api.proxy.confluent.cloud
ConfluentSettings settings3 = ConfluentSettings.newBuilder()
    .setRegion("us-east-1")
    .setCloud("aws")
    .setRestEndpoint("proxy.confluent.cloud")
    // Other required settings...
    .build();
```

## Testing Table Programs

Table programs can be tested in three tiers, from fastest feedback to highest fidelity:

1. **Unit tests on plain logic.** UDFs and other business logic are plain Java classes and can be
   tested with JUnit alone: no Apache Flink, no Confluent Cloud connectivity, and no artifact
   upload required. See `Example_09_FunctionsTest`.
2. **Local pipeline tests on Apache Flink.** Pipeline logic that is structured as
   a function from input `Table`s to an output `Table` (see `VendorsPerBrand` in
   `Example_08_IntegrationAndDeployment`) can be executed locally with mock data from
   `fromValues()`, without Confluent Cloud connectivity. See
   `Example_08_IntegrationAndDeploymentTest` and run with `./mvnw test`.
3. **Integration tests against Confluent Cloud.** The same pipeline logic runs on the real
   service with the exact Confluent semantics, on a Kafka-backed table that is made bounded with
   dynamic options. See `Example_08_IntegrationAndDeploymentIT` and run with `./mvnw verify`.
   These tests require the connection environment variables (see
   [Via Environment Variables](#via-environment-variables)) plus `TARGET_CATALOG` (the name of
   your Confluent Cloud environment) and `TARGET_DATABASE` (the name of a Kafka cluster with
   write access), and fail fast when any are missing, so a CI pipeline cannot silently skip its
   verification step and still report success. To build without Confluent Cloud credentials on
   purpose, skip them explicitly: `./mvnw verify -DskipITs`.

### How local testing works

The Confluent plugin executes all statements on Confluent Cloud; it does not run them locally.
Local tests therefore run on Apache Flink (planner, runtime, and an embedded mini-cluster), which
this project adds as test-scoped dependencies.

The plugin and the Apache Flink planner cannot share a runtime classpath: both register their
Executor and Planner factories under the identifier `default`, and `TableEnvironment.create(...)` fails with
`Multiple factories for identifier 'default'` if both are present. This project resolves the
conflict with classpath exclusions in the `pom.xml`:

- `./mvnw test` (surefire) excludes the Confluent plugin, so unit tests run on Apache Flink.
- `./mvnw verify` (failsafe, test classes named `*IT`) excludes the Apache Flink planner, so
  integration tests run against Confluent Cloud.

Keep pipeline logic free of `io.confluent.flink.plugin` imports so that unit tests can execute it
locally.

NOTE: IDEs ignore these classpath exclusions, so run the tests via `./mvnw test` and
`./mvnw verify` instead of the IDE's test runner. To use the IDE's test runner anyway, replicate
the exclusion in the test's run configuration (IntelliJ IDEA: Modify options -> Modify classpath ->
Exclude): exclude the `confluent-flink-table-api-java-plugin` JAR for unit tests, or the
`flink-table-planner-loader` JAR for integration tests. For production projects, the cleaner
structure is a multi-module build: one module contains the pipeline logic with only
`flink-table-api-java` and the Apache Flink test dependencies, and another module adds the
Confluent plugin and the deployment entrypoints.

### Local testing limitations

Running locally on Apache Flink is not identical to Confluent Cloud:

- The `$rowtime` system column and other Confluent system columns do not exist locally.
- There is no local catalog mirroring your Confluent Cloud schemas. Mock tables are declared
  manually with `fromValues()` and must be kept in sync with the real schemas.
- Confluent-specific SQL syntax (such as `DISTRIBUTED INTO ... BUCKETS`) and Confluent-provided
  functions are not available.

Local tests give fast feedback on transformation logic; integration tests against Confluent Cloud
remain the source of truth.

## CI/CD with GitHub Actions

The repository contains workflows that show how a table program moves through a CI/CD pipeline:

- `.github/workflows/ci.yml` runs in this repository on every pull request: code format check,
  compilation, local unit tests, and the fat JAR build. It requires no Confluent Cloud
  credentials.
- `.github/workflows-examples/deploy.yml` is a template for your own repository: it runs the
  integration tests against Confluent Cloud and then deploys the program by running its `main()`
  method with `--statement-name`, `--application-name`, and `--on-conflict replace`. The statement
  and application names are deployment configuration passed by the pipeline (not hardcoded in the
  program), so the same name is used for deployment and for management; the application name is
  prefixed to the statement name on submission (e.g. `marketplace-analytics-vendors-per-brand`).
  Re-running with unchanged code is idempotent, and a changed pipeline replaces the existing
  statement under the same name.

  `--on-conflict replace` deletes the existing statement and submits a new one: the new statement
  starts from its configured source offsets and does not resume the previous statement's state.
  For stateless pipelines (filters, projections, routing) this has no effect on results. For
  stateful pipelines (aggregations, joins, deduplication, including the aggregation in this
  example) the new statement rebuilds its state by reprocessing from the configured start
  position, so choose the redeploy timing and the source startup mode accordingly.
- `.github/workflows-examples/manage.yml` is a template for explicit lifecycle operations. It runs
  the same deployment JAR with one of the plugin's built-in actions (`list`, `describe`, `stop`,
  `resume`, `delete`) as the first argument; the plugin executes the action instead of deploying,
  so no separate program is needed. Deployment and lifecycle management are separate concerns;
  removing code does not imply that a running statement should be stopped or deleted.

The workflows authenticate via the environment variables described in
[Via Environment Variables](#via-environment-variables), mapped from GitHub Actions secrets. The
target environment and Kafka cluster are selected with the `sql.current-catalog` and
`sql.current-database` configuration options: the deploy workflow passes them on the command line
(from the `TARGET_CATALOG` and `TARGET_DATABASE` secrets), and the integration tests read those
same variables. Because they are deployment configuration rather than source constants,
staging-to-production promotion is a matter of running the same deploy job against different GitHub
environments, each providing its own secrets and protection rules.

## Documentation for Confluent Utilities

### Confluent Tools

The `ConfluentTools` class adds additional methods that can be useful when developing and testing Table API programs.

#### `ConfluentTools.collectChangelog` / `ConfluentTools.printChangelog`

Executes the given table transformations on Confluent Cloud and returns the results locally
as a list of changelog rows. Or prints to the console in a table style.

This method performs `table.execute().collect()` under the hood and consumes a fixed
amount of rows from the returned iterator.

Note: The method can work on both finite and infinite input tables. If the pipeline is
potentially unbounded, it will stop fetching after the desired amount of rows has been
reached.

Examples:
```java
// On Table object
Table table = env.from("examples.marketplace.customers");
List<Row> rows = ConfluentTools.collectChangelog(table, 100);
ConfluentTools.printChangelog(table, 100);

// On TableResult object
TableResult tableResult = env.executeSql("SELECT * FROM examples.marketplace.customers");
List<Row> rows = ConfluentTools.collectChangelog(tableResult, 100);
ConfluentTools.printChangelog(tableResult, 100);
```

Shortcuts:
```java
// For finite (i.e. bounded) tables
ConfluentTools.collectChangelog(table);
ConfluentTools.printChangelog(table);
```

#### `ConfluentTools.collectMaterialized` / `ConfluentTools.printMaterialized`

Executes the given table transformations on Confluent Cloud and returns the results locally
as a materialized changelog. In other words: changes are applied to an in-memory table and
returned as a list of insert-only rows. Or printed to the console in a table style.

This method performs `table.execute().collect()` under the hood and consumes a fixed
amount of rows from the returned iterator.

Note: The method can work on both finite and infinite input tables. If the pipeline is
potentially unbounded, it will stop fetching after the desired amount of rows has been
reached.

```java
// On Table object
Table table = env.from("examples.marketplace.customers");
List<Row> rows = ConfluentTools.collectMaterialized(table, 100);
ConfluentTools.printMaterialized(table, 100);

// On TableResult object
TableResult tableResult = env.executeSql("SELECT * FROM examples.marketplace.customers");
List<Row> rows = ConfluentTools.collectMaterialized(tableResult, 100);
ConfluentTools.printMaterialized(tableResult, 100);
```

Shortcuts:
```java
// For finite (i.e. bounded) tables
ConfluentTools.collectMaterialized(table);
ConfluentTools.printMaterialized(table);
```

#### `ConfluentTools.getStatementName` / `ConfluentTools.stopStatement` / `ConfluentTools.deleteStatement`

Additional lifecycle methods are available to control statements on Confluent Cloud after they have been submitted.

```java
// On TableResult object
TableResult tableResult = env.executeSql("SELECT * FROM examples.marketplace.customers");
String statementName = ConfluentTools.getStatementName(tableResult);
ConfluentTools.stopStatement(tableResult);

// Based on statement name
// Stop a running statement
ConfluentTools.stopStatement(env, "table-api-2024-03-21-150457-36e0dbb2e366-sql");
// Deletes the statement entirely from the system
ConfluentTools.deleteStatement(env, "table-api-2024-03-21-150457-36e0dbb2e366-sql");
```

#### `ConfluentTools.getStatementHandle`

Returns a `StatementHandle` to manage a submitted Flink SQL statement on Confluent Cloud.

The `StatementHandle` class provides a convenient way to control the lifecycle of statements and
retrieve additional information about them. It offers methods to stop, resume, delete statements,
and retrieve warnings.

```java
// From TableResult object
TableResult tableResult = env.executeSql("SELECT * FROM examples.marketplace.customers");
StatementHandle handle = ConfluentTools.getStatementHandle(tableResult);

// From statement name
StatementHandle handle = ConfluentTools.getStatementHandle(env, "table-api-2024-03-21-150457-36e0dbb2e366-sql");
```

Once you have a `StatementHandle`, you can perform various operations:

```java
// Get the statement name
String name = handle.getName();

// Stop the statement execution
handle.stop();

// Resume the statement execution from a previously stopped statement
handle.resume();

// Delete the statement entirely from the system
handle.delete();

// Retrieve warnings associated with this statement
List<StatementWarning> warnings = handle.getWarnings();
for (StatementWarning warning : warnings) {
    System.out.println(warning.getSeverity() + ": " + warning.getMessage());
}

// Get the raw OpenAPI SqlV1Statement response for detailed information
SqlV1Statement sqlStatement = handle.getSqlV1Statement();
```

### Confluent Table Descriptor

A table descriptor for creating tables located in Confluent Cloud programmatically.

Compared to the regular Flink one, this class adds support for Confluent's system columns
and convenience methods for working with Confluent tables.

`forManaged` corresponds to `TableDescriptor.forConector("confluent")`.

```java
TableDescriptor descriptor = ConfluentTableDescriptor.forManaged()
    .schema(
        Schema.newBuilder()
            .column("i", DataTypes.INT())
            .column("s", DataTypes.INT())
            .watermark("$rowtime", $("$rowtime").minus(lit(5).seconds())) // Access $rowtime system column
            .build())
    .build();

env.createTable("t1", descriptor);
```

## Known Limitations

The Table API plugin is in Open Preview stage.

### Unsupported by Table API Plugin

The following feature are currently not supported:

- Temporary catalog objects (including tables, views, functions)
- Custom modules
- Custom catalogs
- Anonymous, inline objects (including functions, data types)
- CompiledPlan features are not supported
- Batch mode
- Restrictions coming from Confluent Cloud
    - custom connectors/formats
    - processing time operations
    - many configuration options
    - limited SQL syntax
    - batch execution mode

### Issues in Open Source Flink

- Both catalog/database must be set or identifiers must be fully qualified. A mixture of setting a current catalog and
  using two-part identifiers can lead to errors.
- Selecting `.rowtime` in windows leads to errors.
- Using `.limit()` can lead to errors.

### Supported API

The following API methods are considered stable and ready to be used:

```text
// TableEnvironment
TableEnvironment.createStatementSet()
TableEnvironment.createTable(String, TableDescriptor)
TableEnvironment.createFunction(...);
TableEnvironment.dropFunction(...);
TableEnvironment.executeSql(String)
TableEnvironment.explainSql(String)
TableEnvironment.from(String)
TableEnvironment.fromValues(...)
TableEnvironment.getConfig()
TableEnvironment.getCurrentCatalog()
TableEnvironment.getCurrentDatabase()
TableEnvironment.listCatalogs()
TableEnvironment.listDatabases()
TableEnvironment.listFunctions()
TableEnvironment.listTables()
TableEnvironment.listTables(String, String)
TableEnvironment.listViews()
TableEnvironment.sqlQuery(String)
TableEnvironment.useCatalog(String)
TableEnvironment.useDatabase(String)

// Table: SQL equivalents
Table.select(...)
Table.as(...)
Table.filter(...)
Table.where(...)
Table.groupBy(...)
Table.distinct()
Table.join(...)
Table.leftOuterJoin(...)
Table.rightOuterJoin(...)
Table.fullOuterJoin(...)
Table.minus(...)
Table.minusAll(...)
Table.union(...)
Table.unionAll(...)
Table.intersect(...)
Table.intersectAll(...)
Table.orderBy(...)
Table.offset(...)
Table.fetch(...)
Table.limit(...)
Table.window(...)
Table.insertInto(String)
Table.executeInsert(String)

// Table: API extensions
Table.getResolvedSchema()
Table.printSchema()
Table.addColumns(...)
Table.addOrReplaceColumns(...)
Table.renameColumns(...)
Table.dropColumns(...)
Table.map(...)
Table.explain()
Table.printExplain()
Table.execute()

// TablePipeline
TablePipeline.explain()
TablePipeline.printExplain()
TablePipeline.execute()

// StatementSet
StatementSet.explain()
StatementSet.add(TablePipeline)
StatementSet.execute()
StatementSet.addInsert(String, Table)
StatementSet.addInsertSql(String)
StatementSet.explain()

// TableResult
TableResult.getJobClient().cancel()
TableResult.await(...)
TableResult.getResolvedSchema()
TableResult.collect()
TableResult.print()

// TableConfig
TableConfig.set(...)

// Expressions
Expressions.*

// Others
TableDescriptor.*
FormatDescriptor.*
Tumble.*
Slide.*
Session.*
Over.*
```

Confluent adds the following classes for more convenience:
```text
ConfluentSettings.*
ConfluentTools.*
ConfluentTableDescriptor.*
```

## Support

Table API goes hand in hand with Flink SQL on Confluent Cloud.
For feature requests or support tickets, use one of the [established channels](https://docs.confluent.io/cloud/current/flink/get-help.html). 
