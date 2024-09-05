# Apache Flink® Table API on Confluent Cloud - Examples for Java

This repository contains examples for running Apache Flink's Table API on Confluent Cloud.

## Introduction to Table API

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

Note: The Table API plugin is in Open Preview stage. Take a look at the *Known Limitation* section below.

### Motivating Example

The following code shows how a Table API program is structured. Subsequent sections will go into more details how you
can use the examples of this repository to play around with Flink on Confluent Cloud.

```java
import io.confluent.flink.plugin.*;

import org.apache.flink.table.api.*;
import org.apache.flink.types.Row;
import static org.apache.flink.table.api.Expressions.*;

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
  Table table = env.from("examples.marketplace.clicks").filter($("user_agent").like("Mozilla%"));

  table.printSchema();
  table.printExplain();

  // Use the provided tools to test on a subset of the streaming data
  List<Row> expected = ConfluentTools.collectMaterialized(table, 50);
  List<Row> actual = List.of(Row.of(42, 500));
  if (!expected.equals(actual)) {
      System.out.println("Results don't match!");
  }
}
```

## Getting Started

### Prerequisites

1. Sign up for Confluent Cloud at [https://confluent.cloud](https://confluent.cloud/signup)
2. [Create a compute pool](https://docs.confluent.io/cloud/current/flink/operate-and-deploy/create-compute-pool.html#create-a-compute-pool-in-ccloud-console)
   in the web UI of Confluent's Cloud Console
3. Optional: [Create a Kafka cluster](https://docs.confluent.io/cloud/current/clusters/create-cluster.html#manage-ak-clusters-on-ccloud)
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

Use Maven to build a JAR file of the project. The included Maven wrapper `mvnw` is useful for a consistent Maven version.
```bash
./mvnw clean package
```

Run an example from the JAR file. No worries the program is read-only so it won't affect your existing
Kafka clusters. All results will be printed to the console.
```bash
cd target
java -jar flink-table-api-java-examples-1.0.jar io.confluent.flink.examples.table.Example_00_HelloWorld
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
client.region=eu-west-1

# Access & compute resources
client.flink-api-key=XXXXXXXXXXXXXXXX
client.flink-api-secret=XxXxXxXxXxXxXxXxXxXxXxXxXxXxXxXxXxXxXxXx
client.organization-id=00000000-0000-0000-0000-000000000000
client.environment-id=env-xxxxx
client.compute-pool-id=lfcp-xxxxxxxxxx
```

Reference the `cloud.properties` file:
```java
// Arbitrary file location in file system
ConfluentSettings settings = ConfluentSettings.fromFile("/path/to/cloud.properties");

// Part of the JAR package (in src/main/resources)
ConfluentSettings settings = ConfluentSettings.fromResource("/cloud.properties");
```

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
ConfluentSettings settings = ConfluentSettingsfromGlobalVariables();
```

### Configuration Options

The following configuration needs to be provided:

| Property key              | CLI arg              | Environment variable | Required | Comment                                                                      |
|---------------------------|----------------------|----------------------|----------|------------------------------------------------------------------------------|
| `client.cloud`            | `--cloud`            | `CLOUD_PROVIDER`     | Y        | Confluent identifier for a cloud provider. For example: `aws`                |
| `client.region`           | `--region`           | `CLOUD_REGION`       | Y        | Confluent identifier for a cloud provider's region. For example: `us-east-1` |
| `client.flink-api-key`    | `--flink-api-key`    | `FLINK_API_KEY`      | Y        | API key for Flink access.                                                    |
| `client.flink-api-secret` | `--flink-api-secret` | `FLINK_API_SECRET`   | Y        | API secret for Flink access.                                                 |
| `client.organization`     | `--organization`     | `ORG_ID`             | Y        | ID of the organization. For example: `b0b21724-4586-4a07-b787-d0bb5aacbf87`  |
| `client.environment`      | `--environment`      | `ENV_ID`             | Y        | ID of the environment. For example: `env-z3y2x1`                             |
| `client.compute-pool`     | `--compute-pool`     | `COMPUTE_POOL_ID`    | Y        | ID of the compute pool. For example: `lfcp-8m03rm`                           |

Additional configuration:

| Property key            | CLI arg            | Environment variable | Required | Comment                                                                                                  |
|-------------------------|--------------------|----------------------|----------|----------------------------------------------------------------------------------------------------------|
| `client.principal`      | `--principal`      | `PRINCIPAL_ID`       | N        | Principal that runs submitted statements. For example: `sa-23kgz4` (for a service account)               |
| `client.context`        | `--context`        |                      | N        | A name for this Table API session. For example: `my_table_program`                                       |
| `client.statement-name` | `--statement-name` |                      | N        | Unique name for statement submission. By default, generated using a UUID.                                |
| `client.rest-endpoint`  | `--rest-endpoint`  | `REST_ENDPOINT`      | N        | URL to the REST endpoint. For example: `proxyto.confluent.cloud`                                         |
| `client.catalog-cache`  |                    |                      | N        | Expiration time for catalog objects. For example: '5 min'. '1 min' by default. '0' disables the caching. |

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
- User-defined functions (including system functions)
- Anonymous, inline objects (including functions, data types)
- CompiledPlan features are not supported
- Batch mode
- Restrictions coming from Confluent Cloud
  - custom connectors/formats
  - processing time operations
  - structured data types
  - many configuration options
  - limited SQL syntax
  - batch execution mode

### Issues in Open Source Flink

- Both catalog/database must be set or identifiers must be fully qualified. A mixture of setting a current catalog and
  using two-part identifiers can lead to errors.
- String concatenation with `.plus` leads to errors. Use `Expressions.concat`.
- Selecting `.rowtime` in windows leads to errors.
- Using `.limit()` can lead to errors.

### Supported API

The following API methods are considered stable and ready to be used:

```text
// TableEnvironment
TableEnvironment.createStatementSet()
TableEnvironment.createTable(String, TableDescriptor)
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
Expressions.* (except for call())

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
