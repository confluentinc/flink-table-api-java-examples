# Apache Flink® Table API on Confluent Cloud - Examples

This repository contains examples for running Apache Flink's Table API on Confluent Cloud.

## Table of Contents

- [Introduction to Table API for Java](#introduction-to-table-api-for-java)
- [Table API on Confluent Cloud](#table-api-on-confluent-cloud)
  - [Motivating Example](#motivating-example)
- [Developer Journey](#developer-journey)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Configure Table API Connection](#configure-table-api-connection)
  - [Run Examples](#run-examples)
  - [Production-ready Configuration](#production-ready-configuration)
  - [Table API Playground using JShell](#table-api-playground-using-jshell)
  - [How to Continue](#how-to-continue)
- [Configuration](#configuration)
  - [Via Properties File](#via-properties-file)
  - [Via Command-Line arguments](#via-command-line-arguments)
  - [Via Code](#via-code)
  - [Via Environment Variables](#via-environment-variables)
  - [Combining a Base Configuration with Command-Line arguments](#combining-a-base-configuration-with-command-line-arguments)
  - [Configuration Options](#configuration-options)
  - [Authentication](#authentication)
  - [Endpoint Configuration](#endpoint-configuration)
  - [client.endpoint-template](#clientendpoint-template)
  - [client.artifact-endpoint-template](#clientartifact-endpoint-template)
  - [client.rest-endpoint (Discouraged)](#clientrest-endpoint-discouraged)
  - [Relationship and Default Behavior](#relationship-and-default-behavior)
  - [Examples](#examples)
- [Testing Table Programs](#testing-table-programs)
  - [How local testing works](#how-local-testing-works)
  - [Local testing limitations](#local-testing-limitations)
  - [Process Table Function Test Harness](#process-table-function-test-harness)
- [CI/CD Integration](#cicd-integration)
  - [Overview](#overview)
  - [Usage](#usage)
  - [Examples](#examples-1)
  - [Exit Codes](#exit-codes)
  - [On-Conflict Behavior](#on-conflict-behavior)
  - [Workflows in this repository](#workflows-in-this-repository)
- [Documentation for Confluent Utilities](#documentation-for-confluent-utilities)
  - [Confluent Tools](#confluent-tools)
  - [Confluent Table Descriptor](#confluent-table-descriptor)
- [Known Limitations](#known-limitations)
  - [Unsupported by Table API Plugin](#unsupported-by-table-api-plugin)
  - [Issues in Open Source Flink](#issues-in-open-source-flink)
  - [Supported API](#supported-api)
- [Support](#support)

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
  // Set up the connection to Confluent Cloud. newBuilderFromResource reads the bundled
  // /cloud.properties file (safe config) with environment variables as a fallback for secrets,
  // and applyArgs layers any command-line arguments on top.
  EnvironmentSettings settings =
      ConfluentSettings.newBuilderFromResource("/cloud.properties").applyArgs(args).build();
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
experiment all the way to a production deployment. They are organized into two packages by theme:

- `io.confluent.flink.examples.interactive` — self-contained programs that run inline and print
  their results to the console, for learning the Table API (`Example_XX`).
- `io.confluent.flink.examples.app` — deployable "apps" that submit long-running background
  statements and are managed via the CI/CD lifecycle actions (`ReferenceApp_XX`).

| Stage | What you do | Where to look |
|-------|-------------|---------------|
| Get started | Configure a connection to Confluent Cloud and run a first program | `Example_00` - `Example_02`, [Getting Started](#getting-started) |
| Build | Transform tables, build pipelines, work with data types, UDFs, and (stateful) process table functions | `Example_03` - `Example_11` |
| Test locally | Run unit tests on mock data, without Confluent Cloud connectivity | `src/test/java/`, [Testing Table Programs](#testing-table-programs) |
| Test on Confluent Cloud | Run integration tests against the real service | `ReferenceApp_01_IntegrationAndDeploymentIT`, [Testing Table Programs](#testing-table-programs) |
| Deploy | Submit statements with deterministic names from a CI/CD pipeline | `ReferenceApp_01_IntegrationAndDeployment`, `ReferenceApp_02_ProcessTableFunction`, [CI/CD with GitHub Actions](#cicd-integration) |
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

### Configure Table API Connection

The quickest way to get connected (examples 00–11):

1. Create your local, git-ignored config file from the template:
   ```bash
   cp src/main/resources/cloud.properties.template src/main/resources/cloud.properties
   ```
2. Fill in the required keys. Since `cloud.properties` is git-ignored, it's safe to add credentials there
   directly too — or set them as the equivalent environment variables instead
   (`GLOBAL_API_KEY`/`GLOBAL_API_SECRET`).

   | Key                          | What it is                          | Where to find it                                                                                                                                        |
   |-------------------------------|--------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
   | `client.cloud`                | Cloud provider of your compute pool | [**Menu** → **Environments**](https://confluent.cloud/environments) → your environment → **Flink** → your compute pool                                  |
   | `client.region`               | Region of your compute pool         | same page as above                                                                                                                                        |
   | `client.organization-id`      | ID of your organization             | [**Menu** → **Settings** → **Organizations**](https://confluent.cloud/settings/organizations)                                                            |
   | `client.environment-id`       | ID of your environment              | [**Menu** → **Environments**](https://confluent.cloud/environments)                                                                                      |
   | `client.compute-pool-id`      | ID of your compute pool             | [**Menu** → **Environments**](https://confluent.cloud/environments) → your environment → **Flink** → your compute pool                                  |
   | `client.global-api-key`       | Cloud API key (Flink + artifacts)   | [**Menu** → **Settings** → **API keys**](https://confluent.cloud/settings/api-keys)                                                                      |
   | `client.global-api-secret`    | Cloud API secret (Flink + artifacts) | [**Menu** → **Settings** → **API keys**](https://confluent.cloud/settings/api-keys)                                                                      |

That's enough to run the interactive examples directly.

There are two ways to supply this configuration, and it helps to know which is which:

- **Hard-coded `cloud.properties`** — used by the interactive examples via `ConfluentSettings.newBuilderFromResource(...)`. This is the simplest way to get set up locally: one file with everything filled in.
- **Dynamic environment variables & properties files** — used by the reference apps via `ConfluentSettings.newBuilder()`, and loaded from `.env` by the `bin/` scripts. This suits production and CI/CD: secrets stay out of committed files, and the same build binds to different configuration per deploy stage or region without code changes.

For the production-oriented setup see [Production-ready Configuration](#production-ready-configuration); for the full set of configuration options see [Configuration](#configuration).

### Run Examples

Examples are runnable from the command-line or an IDE. Command-line is convenient for CI/CD integration. IDE is
recommended for development, debugging, and playing around in an interactive manner.

The numbered learning examples live in `src/main/java/io/confluent/flink/examples/interactive/` (the
deployable reference apps are in `app/`, and the shared functions they use in `functions/`). Each file contains a Java `main()`
method with a table program that can be executed individually. Every example program covers a different topic to learn
more about how Table API can be used. It is recommended to go through the examples in the defined order as they partially
build on top of each other.

The first few examples are read-only and run against the shared `examples.marketplace` catalog out of the box. Examples that
create tables, register functions, or submit statements additionally need a current catalog and database with write
access — set `sql.current-catalog` (your environment) and `sql.current-database` (a Kafka cluster) in `cloud.properties`
(see the documented keys in `cloud.properties.template`). Each example's docstring says whether it writes to Confluent Cloud.

Clone this repository to your local computer, or download it as a ZIP file and extract it.
```bash
git clone https://github.com/confluentinc/flink-table-api-java-examples.git
```

#### Via Provided Script

Two convenience scripts load `.env` (connection config + secrets), build the JAR if needed, and run:
```bash
./bin/run.sh Example_00_HelloWorld                                     # run an example
./bin/run.sh ReferenceApp_01_IntegrationAndDeployment --statement-name demo # pass program args
./bin/jshell.sh                                                        # interactive JShell session
```
Builds, tests, and cleanup are plain Maven commands: `./mvnw package`, `./mvnw test`, `./mvnw clean`.

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
java -cp flink-table-api-java-examples-1.0.jar io.confluent.flink.examples.interactive.Example_00_HelloWorld
```

An output similar to the following means that you are able to run the examples, but require some configuration of your cloud connection:
```text
Exception in thread "main" io.confluent.flink.plugin.ConfluentFlinkException: Parameter 'client.organization-id' not found.
```

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

### Production-ready Configuration

Above we covered the minimal configuration setup via `.properties` file. For a full set of configuration options and patterns, see [Configuration](#configuration).

But what should your config setup look like for a production-ready app? Let's go over an opinionated setup and the rationale behind it.

First, what does production-ready mean? Let's take the following assumptions:

1. The app should be deployed across multiple environments (e.g. `dev` -> `staging` -> `production` with potential fan-out to multiple production regions)
2. The non-secret connection information should be committed to the repository directly (e.g. org ID, compute pool ID, ...) and varies per deploy environment
3. The API key secrets must NOT be committed to git, and are instead injected per deploy environment by CICD tools, and by local secret injection on each engineer's machine for `dev`.

Now we can sketch an opinonated path to realize these requirements:

1. Setup 1 properties file per deploy environment with all necessary non-secret information, e.g. `cloud.properties` is replaced by `dev.properties`, `staging.properties`, `prod-noram.properties`, `prod-emea.properties`, and `prod-apac.properties`.
2. Adjust the app code to remove hard-coded reference to `cloud.properties`. Instead follow the structure in `ReferenceApp_01_IntegrationAndDeployment` using `ConfluentSettings.newBuilder()` instead of `ConfluentSettings.newBuilderFromResource` to infer properties file from the environment.
    ```
    EnvironmentSettings settings = ConfluentSettings.newBuilder()
        .setApplicationName("<app-name>")
        .applyArgs(args)
        .build();
    ```
3. To repair your local setup, create a `.env` file which contains your `dev` API key and a pointer to `dev.properties`.
    ```
    FLINK_PROPERTIES=./src/main/resources/dev.properties
    GLOBAL_API_KEY=<key>
    GLOBAL_API_SECRET=<secret>
    ```
4. From your CICD tool, use your secret management tool of choice to load the appropriate API key secrets and `FLINK_PROPERTIES` value per deploy environment.

### Table API Playground using JShell

For convenience, the repository also contains a [JShell](https://openjdk.org/jeps/222) init script for playing around with
Table API in an interactive manner.

1. Fill in your configuration and secrets as described in
   [Configure Table API Connection](#configure-table-api-connection). JShell connects via
   `ConfluentSettings.fromGlobalVariables()`, so it reads the connection options and secrets from the
   environment (loaded from `.env`).

2. Start the session — the script loads `.env` and builds the JAR if needed:
   ```bash
   ./bin/jshell.sh
   ```
   (Equivalent to building with `./mvnw package` and then running `jshell --class-path
   ./target/flink-table-api-java-examples-1.0.jar --startup ./jshell-init.jsh` with `.env` loaded.)

3. The `TableEnvironment` is pre-initialized from the configuration above and available under `env`.

4. Run your first "Hello world!" using `env.executeSql("SELECT 'Hello world!'").print();`

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

For production, a properties file, command-line arguments, code, and environment variables can be
combined.

**Resolution order.** Command-line arguments (via `applyArgs`), the properties file, and builder
setters all write into the same configuration, where the **last writer wins** (in call order).
Environment variables act as a **fallback**: they are consulted only for keys not otherwise set. For
the recommended chain below, the effective precedence (highest to lowest) is:

1. Command-line arguments (`applyArgs`)
2. Properties file and code setters
3. Environment variables (fallback — e.g. secrets)

A multi-layered configuration can look like:
```java
public static void main(String[] args) {
  // newBuilder() reads the FLINK_PROPERTIES file (if set) and environment variables (secrets).
  // Code sets defaults; applyArgs(args) overrides them per invocation.
  EnvironmentSettings settings = ConfluentSettings.newBuilder()
      .setApplicationName("my-table-program")       // overridable by --application-name
      .setOption("sql.local-time-zone", "UTC")
      .applyArgs(args)
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
client.global-api-key=key
client.global-api-secret=secret
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
  --global-api-key key \
  --global-api-secret secret \
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
    .setGlobalApiKey("key")
    .setGlobalApiSecret("secret")
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
export GLOBAL_API_KEY="key"
export GLOBAL_API_SECRET="secret"
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

### Combining a Base Configuration with Command-Line arguments

Use `applyArgs` to layer command-line arguments on top of an existing builder. This lets a single program combine a
base configuration (from a properties file, for example) with per-invocation arguments:

```java
public static void main(String[] args) {
  ConfluentSettings settings = ConfluentSettings.newBuilder() // FLINK_PROPERTIES file + environment-variable fallback
    .setApplicationName("my-table-program")                   // code default, overridable by --application-name
    .applyArgs(args)                                          // per-invocation overrides
    .build();
}
```

### Configuration Options

The following configuration needs to be provided:

| Property key               | CLI arg               | Environment variable | Required | Comment                                                                                                    |
|----------------------------|-----------------------|----------------------|----------|------------------------------------------------------------------------------------------------------------|
| `client.cloud`             | `--cloud`             | `CLOUD_PROVIDER`     | Y        | Confluent identifier for a cloud provider. For example: `aws`                                              |
| `client.region`            | `--region`            | `CLOUD_REGION`       | Y        | Confluent identifier for a cloud provider's region. For example: `us-east-1`                               |
| `client.flink-api-key`     | `--flink-api-key`     | `FLINK_API_KEY`      | Y¹       | API key for Flink access.                                                                                  |
| `client.flink-api-secret`  | `--flink-api-secret`  | `FLINK_API_SECRET`   | Y¹       | API secret for Flink access.                                                                               |
| `client.global-api-key`    | `--global-api-key`    | `GLOBAL_API_KEY`     | N        | API key for both Flink access and Artifact creation. See the [Authentication](#authentication) section.    |
| `client.global-api-secret` | `--global-api-secret` | `GLOBAL_API_SECRET`  | N        | API secret for both Flink access and Artifact creation. See the [Authentication](#authentication) section. |
| `client.organization-id`   | `--organization-id`   | `ORG_ID`             | Y        | ID of the organization. For example: `b0b21724-4586-4a07-b787-d0bb5aacbf87`                                |
| `client.environment-id`    | `--environment-id`    | `ENV_ID`             | Y        | ID of the environment. For example: `env-z3y2x1`                                                           |
| `client.compute-pool-id`   | `--compute-pool-id`   | `COMPUTE_POOL_ID`    | Y        | ID of the compute pool. For example: `lfcp-8m03rm`                                                         |

¹ Required unless a global API key and secret are configured. See the [Authentication](#authentication) section.

Required configuration for supporting UDF uploads:

Note: Artifact key and secret can be created via Web Console under `API keys` -> `Cloud resource management`.

| Property key                 | CLI arg                 | Environment variable   | Comment                           |
|------------------------------|-------------------------|------------------------|-----------------------------------|
| `client.artifact-api-key`    | `--artifact-api-key`    | `ARTIFACT_API_KEY`     | API key for Artifact creation.    |
| `client.artifact-api-secret` | `--artifact-api-secret` | `ARTIFACT_API_SECRET`  | API secret for Artifact creation. |

Additional configuration:

| Property key                        | CLI arg                        | Environment variable         | Required | Comment                                                                                                                                                                                         |
|-------------------------------------|--------------------------------|------------------------------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `client.endpoint-template`          | `--endpoint-template`          | `ENDPOINT_TEMPLATE`          | N        | A template for the endpoint URL. For example: `https://flinkpls-dom123.{region}.{cloud}.confluent.cloud`                                                                                        |
| `client.artifact-endpoint-template` | `--artifact-endpoint-template` | `ARTIFACT_ENDPOINT_TEMPLATE` | N        | A template for the artifact endpoint URL. For example: `https://api.{region}.{cloud}.confluent.cloud`                                                                                           |
| `client.principal-id`               | `--principal-id`               | `PRINCIPAL_ID`               | N        | Principal that runs submitted statements. For example: `sa-23kgz4` (for a service account)                                                                                                      |
| `client.application-name`           | `--application-name`           | `APPLICATION_NAME`           | N        | A name for this Table API application. Serves as a namespace prefix for all statement names. Lowercase alphanumeric characters and hyphens only, max 100 chars. For example: `my-table-program` |
| `client.statement-name`             | `--statement-name`             | `STATEMENT_NAME`             | N        | Unique name for statement submission. If an application name is set, it is prefixed. By default, generated using a UUID.                                                                        |
| `client.action.kind`                |                                |                              | N        | Lifecycle action for CI/CD integration. One of `list`, `describe`, `resume`, `stop`, `delete`. See [CI/CD with GitHub Actions](#cicd-integration).                                      |
| `client.action.skip-exit`           |                                |                              | N        | Skip `System.exit()` after an action runs. Default: `false`.                                                                                                                                    |
| `client.wait`                       | `--wait [<duration>]`          |                              | N        | When set, lifecycle actions (`resume`, `stop`, `delete`) block until the target phase is reached or `client.timeout` elapses. An optional duration overrides the timeout. Default: `false`.     |
| `client.timeout`                    |                                |                              | N        | Maximum time to wait when `client.wait` is set. For example: `5min` or `300s`. Default: `300s`.                                                                                                 |
| `client.on-conflict`                | `--on-conflict`                | `ON_CONFLICT`                | N        | Behavior when a statement with the same name already exists with a different spec. `fail` (default) or `replace`. Requires `client.application-name`.                                           |
| `client.rest-endpoint`              | `--rest-endpoint`              | `REST_ENDPOINT`              | N        | URL to the REST endpoint. For example: `proxyto.confluent.cloud`                                                                                                                                |
| `client.catalog-cache`              |                                |                              | N        | Expiration time for catalog objects. For example: `5 min`. `1 min` by default. `0` disables the caching.                                                                                        |
| `client.tmp-dir`                    | `--tmp-dir`                    |                              | N        | Directory for temporary files created by the plugin, e.g. UDF jars. For example: `/tmp`. By default value of `java.io.tmpdir`.                                                                  |

### Authentication

The plugin authenticates against the Confluent Cloud REST APIs using the mode selected by `client.auth-mode`. If it is not set, the default is `api-key`.

| Property key       | CLI arg       | Environment variable | Required              | Comment                                                                                |
|--------------------|---------------|----------------------|-----------------------|----------------------------------------------------------------------------------------|
| `client.auth-mode` | `--auth-mode` | `AUTH_MODE`          | N (default `api-key`) | One of `api-key`, `oauth-client-credentials`, `oauth-static-token` (case-insensitive). |

#### API Keys (default)

In the default `api-key` mode, the plugin first tries the global API key and secret (`client.global-api-key` / `client.global-api-secret`), which work for both Flink access and Artifact creation. If no global key and secret are set, it falls back to the dedicated Flink API key and secret (`client.flink-api-key` / `client.flink-api-secret`) and, only when you upload UDF artifacts, a separate Artifact API key and secret (`client.artifact-api-key` / `client.artifact-api-secret`). All of these keys are listed in [Configuration Options](#configuration-options) above.

#### OAuth

The plugin supports two OAuth-based authentication modes:

- `oauth-client-credentials` (`client.auth-mode=oauth-client-credentials`): the plugin fetches and refreshes access tokens from an external IdP using the OAuth 2.0 client credentials flow.
- `oauth-static-token` (`client.auth-mode=oauth-static-token`): you supply a pre-issued bearer token as a string via `client.oauth.external-access-token`, or programmatically via the Java builder `setOAuthTokenProvider(OAuthTokenProvider)` for cloud-native flows (for example Azure Managed Identity or AWS IAM workload identity).

In both modes, a Confluent Cloud identity pool with the correct permission assignments must exist for the intended workload. When running in an OAuth mode, UDF artifact uploads work without further configuration. For detailed setup instructions, see the [Confluent Cloud OAuth Guide](https://docs.confluent.io/cloud/current/security/authenticate/workload-identities/identity-providers/oauth/overview.html).

| Property key                          | CLI arg                          | Environment variable           | Required                                    | Comment                                                                        |
|---------------------------------------|----------------------------------|--------------------------------|---------------------------------------------|--------------------------------------------------------------------------------|
| `client.oauth.external-token-url`     | `--oauth.external-token-url`     | `OAUTH_EXTERNAL_TOKEN_URL`     | Y (`OAUTH_CLIENT_CREDENTIALS`)              | URL of the IdP's OAuth 2.0 token endpoint.                                     |
| `client.oauth.external-client-id`     | `--oauth.external-client-id`     | `OAUTH_EXTERNAL_CLIENT_ID`     | Y (`OAUTH_CLIENT_CREDENTIALS`)              | Client ID registered with the IdP.                                             |
| `client.oauth.external-client-secret` | `--oauth.external-client-secret` | `OAUTH_EXTERNAL_CLIENT_SECRET` | Y (`OAUTH_CLIENT_CREDENTIALS`)              | Client Secret for the configured Client ID.                                    |
| `client.oauth.external-token-scope`   | `--oauth.external-token-scope`   | `OAUTH_EXTERNAL_TOKEN_SCOPE`   | N                                           | Additional scopes attached during the client credentials flow.                 |
| `client.oauth.external-access-token`  | `--oauth.external-access-token`  | `OAUTH_EXTERNAL_ACCESS_TOKEN`  | Y (`OAUTH_STATIC_TOKEN` without a callback) | Pre-issued bearer token. Provided as a string, no refreshes will be performed. |
| `client.oauth.identity-pool-id`       | `--oauth.identity-pool-id`       | `OAUTH_IDENTITY_POOL_ID`       | Y (any OAuth mode)                          | Confluent Cloud identity pool ID. For example: `pool-xxxxx`.                   |

Client credentials flow via environment variables:

```bash
export AUTH_MODE="oauth-client-credentials"
export OAUTH_EXTERNAL_TOKEN_URL="https://mycompany.okta.com/oauth2/abc123/v1/token"
export OAUTH_EXTERNAL_CLIENT_ID="cid"
export OAUTH_EXTERNAL_CLIENT_SECRET="csec"
export OAUTH_IDENTITY_POOL_ID="pool-xxxxx"
export OAUTH_EXTERNAL_TOKEN_SCOPE="write:service"
```

Static token via environment variables:

```bash
export AUTH_MODE="oauth-static-token"
export OAUTH_EXTERNAL_ACCESS_TOKEN="eyJ-..."
export OAUTH_IDENTITY_POOL_ID="pool-xxxxx"
```

For cloud-native flows, provide a token programmatically with `setOAuthTokenProvider`. The following example wraps Azure Managed Identity; the same `OAuthTokenProvider` interface can wrap AWS STS `AssumeRoleWithWebIdentity` or any other provider:

```java
TokenCredential credential = new DefaultAzureCredentialBuilder().build();
TokenRequestContext request =
    new TokenRequestContext().addScopes("api://<client_id>/.default");

ConfluentSettings settings = ConfluentSettings.newBuilder()
    .setAuthMode(AuthMode.OAUTH_STATIC_TOKEN)
    .setOAuthIdentityPoolId("pool-xxxxx")
    .setOAuthTokenProvider(
        () -> {
          AccessToken at = credential.getToken(request).block();
          return new OAuthToken(at.getToken(), at.getExpiresAt().toInstant());
        })
    .build();
```

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
   upload required. See `Example_08_FunctionsTest`.
2. **Local pipeline tests on Apache Flink.** Pipeline logic that is structured as
   a function from input `Table`s to an output `Table` (see `VendorsPerBrand` in
   `ReferenceApp_01_IntegrationAndDeployment`) can be executed locally with mock data from
   `fromValues()`, without Confluent Cloud connectivity. See
   `ReferenceApp_01_IntegrationAndDeploymentTest` and run with `./mvnw test`.
3. **Integration tests against Confluent Cloud.** The same pipeline logic runs on the real
   service with the exact Confluent semantics, on a Kafka-backed table that is made bounded with
   dynamic options. See `ReferenceApp_01_IntegrationAndDeploymentIT` and run with `./mvnw verify`.
   These tests require the connection configuration (see
   [Via Environment Variables](#via-environment-variables)) plus a current catalog and database
   (`sql.current-catalog` / `sql.current-database`) pointing to a Confluent environment and a Kafka
   cluster with write access, since they provision a mock Kafka-backed table. A run without that
   configuration fails rather than silently skipping. Note that `verify` runs against your live
   environment: it creates and drops a real Kafka topic and consumes compute (CFUs) while it runs.
   To build without Confluent Cloud credentials on purpose, skip the integration tests explicitly:
   `./mvnw verify -DskipITs`.

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

### Process Table Function Test Harness

The `ProcessTableFunctionTestHarness` is available for unit testing Process Table Functions (PTFs)
before this capability is released in an official Apache Flink release. The harness ships with
`confluent-flink-table-api-java-plugin`, so depending on the plugin (as this project already does)
is enough to use it in your tests.

For guidance on how to use the `ProcessTableFunctionTestHarness` for testing PTFs, please consult the
[nightly documentation](https://nightlies.apache.org/flink/flink-docs-master/docs/dev/table/functions/ptfs/#testing-process-table-functions).

## CI/CD Integration

The Confluent Flink plugin supports lifecycle management actions for seamless integration into CI/CD pipelines.
This allows you to manage Flink statements directly from your deployment scripts without writing SQL or using the API.

### Overview

Instead of managing statement lifecycle programmatically or through SQL, you can invoke your JAR with an action instruction.
This is particularly useful for:

- **Continuous Deployment**: Manage statement lifecycle before deploying new versions
- **Resource Cleanup**: Clean up statements as part of environment teardown
- **Rollback Procedures**: Handle statements during rollback workflows
- **Testing Pipelines**: Clean up test statements after integration tests

### Usage

Actions are specified as the first argument that is not prefixed with `--` when running your JAR.
Only one action is allowed per execution.

**Important**: To use this feature, your Table API application must parse command-line arguments using
`ConfluentSettings.fromArgs(args)`, `ConfluentSettings.newBuilderFromArgs(args)`, or `applyArgs(args)`
on a builder (as `ReferenceApp_01_IntegrationAndDeployment` does) in its `main()` method. When an action is
detected, it will be executed and the JVM will terminate early (via `System.exit()`), preventing the
rest of your application logic from running.

```java
public static void main(String[] args) {
    // This will automatically detect and execute actions if present
    // If an action is specified, the program will exit here
    EnvironmentSettings settings = ConfluentSettings.newBuilder().applyArgs(args).build();
    TableEnvironment env = TableEnvironment.create(settings);

    // Your application logic here (only runs if no action was specified)
    // ...
}
```

To disable the automatic exit behavior (e.g., for testing), set the internal configuration option `client.action.skip-exit=true`.
The implementation must catch a `ActionCompletedException` in case of errors.

#### Basic Syntax

```bash
java -jar my-table-program.jar <action> [options]
```

Where `<action>` is one of:
- `list` - Lists statements belonging to the application or a specific statement
- `describe` - Shows full JSON details for a specific statement
- `resume` - Resumes a stopped statement
- `stop` - Stops a running statement
- `delete` - Deletes a statement entirely from the system

#### Required Configuration

When using `describe`, `resume`, `stop` or `delete` actions, you **must** provide a static statement name via one of these methods:

1. **Command-line argument:**
   ```bash
   java -jar my-table-program.jar stop --statement-name my-query [other options]
   ```

2. **Environment variable:**
   ```bash
   export STATEMENT_NAME="my-query"
   java -jar my-table-program.jar stop [other options]
   ```

3. **Properties file:**
   ```properties
   # cloud.properties
   client.statement-name=my-query
   ```

If an application name is configured, it will be automatically prefixed to the statement name
(e.g., `my-app-my-query`).

**Note:** The `list` action does **not** require a statement name. When an application name is configured, it will list all statements matching that application name prefix. When no application name is configured, it will list **all statements** in the environment (which may be expensive in shared environments). For CI/CD usage, it is recommended to always configure an application name to scope the listing. Optionally, you can provide a statement name to list only a specific statement. The `describe`, `stop`, and `delete` actions all require a statement name.

#### Waiting for Completion

By default, `resume`, `stop`, and `delete` return as soon as the Confluent Cloud API has accepted the request: the
statement may still be transitioning in the background. For CI/CD pipelines where the next step depends on the new
phase being reached, pass `--wait` to block until the action has fully taken effect:

- `resume` waits until the statement reaches `RUNNING`.
- `stop` waits until the statement reaches `STOPPED`.
- `delete` waits until the statement does not exist.

Tune the maximum wait by passing a duration directly to `--wait` (e.g. `--wait 10min`, default: `300s`). Durations
accept values like `30s`, `5min`, or `2h`. If no duration is passed the default timeout will be used. If the target
phase is not reached before the timeout elapses, the action fails with exit code 1.

The `list` and `describe` actions ignore `--wait`.

### Examples

#### Listing Statements

The `list` action displays all statements associated with your application in a tabular format showing:
- **Kind**: Always "Statement" (prepared for future resource types)
- **Name**: The full statement name
- **Phase**: The current status (e.g., RUNNING, COMPLETED, STOPPED)
- **Created**: When the statement was created

```bash
# List all statements for an application
java -jar marketplace-analytics.jar list \
  --application-name marketplace-analytics \
  --cloud aws \
  --region us-east-1 \
  --organization-id b0b21724-4586-4a07-b787-d0bb5aacbf87 \
  --environment-id env-z3y2x1 \
  --compute-pool-id lfcp-8m03rm \
  --global-api-key <key> \
  --global-api-secret <secret>
```

Example output:
```
+------------+--------------------------------------------+-----------+---------------------------+
| Kind       | Name                                       | Phase     | Created                   |
+------------+--------------------------------------------+-----------+---------------------------+
| Statement  | marketplace-analytics-query-1              | RUNNING   | 2026-05-08T10:30:00Z      |
| Statement  | marketplace-analytics-query-2              | COMPLETED | 2026-05-08T09:15:00Z      |
| Statement  | marketplace-analytics-experimental-query   | STOPPED   | 2026-05-07T14:22:00Z      |
+------------+--------------------------------------------+-----------+---------------------------+
```

```bash
# List a specific statement
java -jar marketplace-analytics.jar list \
  --statement-name marketplace-query \
  --application-name marketplace-analytics \
  --cloud aws \
  --region us-east-1 \
  --organization-id b0b21724-4586-4a07-b787-d0bb5aacbf87 \
  --environment-id env-z3y2x1 \
  --compute-pool-id lfcp-8m03rm \
  --global-api-key <key> \
  --global-api-secret <secret>
```

Example output:
```
+------------+-----------------------------------------+-----------+---------------------------+
| Kind       | Name                                    | Phase     | Created                   |
+------------+-----------------------------------------+-----------+---------------------------+
| Statement  | marketplace-analytics-marketplace-query | RUNNING   | 2026-05-08T10:30:00Z      |
+------------+-----------------------------------------+-----------+---------------------------+
```

#### Describing a Statement

The `describe` action displays the complete JSON representation of a statement from the Confluent Cloud SQL API (OpenAPI specification). This includes all metadata, status details, configuration, and results.

```bash
# Describe a specific statement
java -jar marketplace-analytics.jar describe \
  --statement-name marketplace-query \
  --application-name marketplace-analytics \
  --cloud aws \
  --region us-east-1 \
  --organization-id b0b21724-4586-4a07-b787-d0bb5aacbf87 \
  --environment-id env-z3y2x1 \
  --compute-pool-id lfcp-8m03rm \
  --global-api-key <key> \
  --global-api-secret <secret>
```

Example output:
```json
{
  "api_version": "sql/v1",
  "kind": "Statement",
  "metadata": {
    "self": "https://api.confluent.cloud/sql/v1/organizations/b0b21724-4586-4a07-b787-d0bb5aacbf87/environments/env-z3y2x1/statements/marketplace-analytics-marketplace-query",
    "created_at": "2026-05-08T10:30:00Z",
    "updated_at": "2026-05-08T10:30:15Z"
  },
  "name": "marketplace-analytics-marketplace-query",
  "organization_id": "b0b21724-4586-4a07-b787-d0bb5aacbf87",
  "environment_id": "env-z3y2x1",
  "spec": {
    "statement": "SELECT * FROM marketplace_events",
    "compute_pool_id": "lfcp-8m03rm"
  },
  "status": {
    "phase": "RUNNING",
    "detail": "Statement is running successfully"
  }
}
```

#### Resuming a Statement

```bash
java -jar marketplace-analytics.jar resume \
  --statement-name marketplace-query \
  --application-name marketplace-analytics \
  --cloud aws \
  --region us-east-1 \
  --organization-id b0b21724-4586-4a07-b787-d0bb5aacbf87 \
  --environment-id env-z3y2x1 \
  --compute-pool-id lfcp-8m03rm \
  --global-api-key <key> \
  --global-api-secret <secret>
```

```bash
# Block until the statement reaches RUNNING (fails with exit 1 if not running within the timeout)
java -jar marketplace-analytics.jar resume \
  --statement-name marketplace-query \
  --wait 10min \
  --application-name marketplace-analytics \
  # ... other configuration
```

#### Stopping a Statement

```bash
java -jar marketplace-analytics.jar stop \
  --statement-name marketplace-query \
  --application-name marketplace-analytics \
  --cloud aws \
  --region us-east-1 \
  --organization-id b0b21724-4586-4a07-b787-d0bb5aacbf87 \
  --environment-id env-z3y2x1 \
  --compute-pool-id lfcp-8m03rm \
  --global-api-key <key> \
  --global-api-secret <secret>
```

```bash
# Block until the statement reaches STOPPED (fails with exit 1 if not stopped within the timeout)
java -jar marketplace-analytics.jar stop \
  --statement-name marketplace-query \
  --wait 10min \
  --application-name marketplace-analytics \
  # ... other configuration
```

#### Deleting a Statement

```bash
java -jar marketplace-analytics.jar delete \
  --statement-name marketplace-query \
  --application-name marketplace-analytics \
  --cloud aws \
  --region us-east-1 \
  --organization-id b0b21724-4586-4a07-b787-d0bb5aacbf87 \
  --environment-id env-z3y2x1 \
  --compute-pool-id lfcp-8m03rm \
  --global-api-key <key> \
  --global-api-secret <secret>
```

```bash
# Block until the statement is fully deleted (fails with exit 1 if not deleted within the timeout)
java -jar marketplace-analytics.jar delete \
  --statement-name marketplace-query \
  --wait 10min \
  --application-name marketplace-analytics \
  # ... other configuration
```

### Exit Codes

The action execution follows standard exit code conventions:

- **Exit code 0**: Action completed successfully
- **Exit code 1**: Action failed (e.g., statement not found, permission denied, missing configuration, timeout elapsed)

This makes it easy to integrate into CI/CD pipelines and handle failures appropriately.

### On-Conflict Behavior

When a statement with the same name already exists but with a different spec,
the plugin's default behavior (`client.on-conflict=fail`) is to propagate the underlying conflict as a
`ConfluentFlinkException`. Set `client.on-conflict=replace` (or pass `--on-conflict replace`) if, despite
the conflict, the submission should be enforced.

In that case the conflicting statement is deleted and the submission is retried once.
`replace` **always requires** `client.application-name` (or `APPLICATION_NAME`) to be set, so that
statement names are stable across runs and a redeploy targets the same name.

```bash
java -jar target/marketplace-analytics.jar \
  --application-name marketplace-analytics \
  --on-conflict replace \
  --cloud aws \
  --region us-east-1 \
  ...
```

### Workflows in this repository

The repository contains workflows that show how a table program moves through a CI/CD pipeline:

- `.github/workflows/ci.yml` runs in this repository on every pull request: code format check,
  compilation, local unit tests, and the fat JAR build. It requires no Confluent Cloud
  credentials.
- `.github/workflows-examples/deploy.yml` is a template for your own repository: it runs the
  integration tests against Confluent Cloud and then deploys the program by running its `main()`
  method with `--statement-name`, `--application-name`, and `--on-conflict replace`. The statement
  name is deployment configuration passed by the pipeline, and the application name has a default set
  in the program that the pipeline overrides with `--application-name`, so the same names are used for
  deployment and for management; the application name is prefixed to the statement name on submission
  (e.g. `marketplace-analytics-vendors-per-brand`).
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
(from the `TARGET_CATALOG` and `TARGET_DATABASE` secrets); the integration tests select the target
the same way, via `sql.current-catalog` / `sql.current-database`. Because they are deployment configuration rather than source constants,
staging-to-production promotion is a matter of running the same deploy job against different GitHub
environments, each providing its own secrets and protection rules.

## Documentation for Confluent Utilities

### Confluent Tools

The `ConfluentTools` class adds additional methods that can be useful when developing and testing Table API programs.

#### `ConfluentTools.setStatementName`

Sets the statement name for the next statement submission.

A statement name must be unique within an environment and cloud region for a given organization. By default, statement names are auto-generated using a UUID.

**Important:** If you configured an application name via `ConfluentSettings.setApplicationName()`, it will be automatically prefixed to the statement name you provide here. For example, if your application name is `"myapp"` and you set the statement name to `"query1"`, the final statement name will be `"myapp-query1"`.

If you did not configure an application name and use this method to set an explicit statement name, the statement name you provide will be used as-is (fully qualified).

If you plan to submit multiple statements and use this method to manually set names, make sure to set a new name before each submission.

**Naming constraints:**
- Must contain only lowercase alphanumeric characters and hyphens
- Must start and end with an alphanumeric character (not a hyphen)
- Maximum length: 100 characters (including the application name prefix if configured)

```java
// Example with application name set to "myapp"
// Final statement name will be: "myapp-my-custom-statement-name"
ConfluentTools.setStatementName(env, "my-custom-statement-name");
TableResult tableResult = env.executeSql("SELECT * FROM examples.marketplace.customers");

// For multiple statements, set a new name before each submission
// Final statement name will be: "myapp-another-statement-name"
ConfluentTools.setStatementName(env, "another-statement-name");
TableResult anotherResult = env.executeSql("SELECT * FROM examples.marketplace.products");

// Example without application name (no prefix applied)
// Final statement name will be exactly: "my-fully-qualified-statement-name"
ConfluentTools.setStatementName(env, "my-fully-qualified-statement-name");
TableResult result = env.executeSql("SELECT * FROM examples.marketplace.products");
```

**Alternative: Set statement name via ConfluentSettings**

For single-statement programs, you can set the statement name when building the settings:

```java
ConfluentSettings settings = ConfluentSettings.newBuilder()
    .setApplicationName("my-table-program")
    .setStatementName("my-custom-query")
    // ... other settings
    .build();
TableEnvironment env = TableEnvironment.create(settings);
// This statement will use the configured name 'my-table-program-my-custom-query'
env.executeSql("SELECT * FROM examples.marketplace.customers").print();
```

**Alternative: Set statement name via CLI, environment variable, or properties file**

For single-statement programs, you can also set the statement name when starting your application:

```bash
# Via command-line argument
java -jar my-table-program.jar --statement-name my-custom-query

# Via environment variable
export STATEMENT_NAME="my-custom-query"
java -jar my-table-program.jar

# Via properties file (cloud.properties)
# client.statement-name=my-custom-query
```

Note: When set via `ConfluentSettings`, CLI arguments, environment variables, or properties file, the statement name
applies globally to the TableEnvironment and is suitable for single-statement programs. For programs that submit
multiple statements, use `ConfluentTools.setStatementName()` to set a new name before each submission.

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

#### `ConfluentTools.deleteArtifact`

Deletes a UDF artifact from Confluent Cloud by its id (e.g. `cfa-...`). This is useful to
clean up artifacts that were uploaded for inline UDFs but are no longer referenced by any
statement.

Requires artifact credentials: either a global API key/secret (`client.global-api-key` /
`client.global-api-secret`) or a dedicated Artifact API key/secret (`client.artifact-api-key` /
`client.artifact-api-secret`).

```java
ConfluentTools.deleteArtifact(env, "cfa-abc123");
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
TableEnvironment.createView(String, Table)  // inline/unregistered UDFs not supported
TableEnvironment.createView(String, Table, boolean) // inline/unregistered UDFs not supported
TableEnvironment.createFunction(...);
TableEnvironment.dropFunction(...);
TableEnvironment.dropTable(String);
TableEnvironment.dropView(String);
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
Expressions.* (call() supports calling functions by identifiers)

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
