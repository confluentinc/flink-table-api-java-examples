import io.confluent.flink.plugin.*;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.annotation.*;
import org.apache.flink.table.api.*;
import org.apache.flink.table.api.dataview.*;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.catalog.UniqueConstraint;
import org.apache.flink.table.catalog.WatermarkSpec;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.expressions.TimeIntervalUnit;
import org.apache.flink.table.expressions.TimePointUnit;
import org.apache.flink.table.functions.AggregateFunction;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.table.functions.FunctionDefinition;
import org.apache.flink.table.functions.FunctionRequirement;
import org.apache.flink.table.functions.ScalarFunction;
import org.apache.flink.table.functions.TableAggregateFunction;
import org.apache.flink.table.functions.TableFunction;
import org.apache.flink.table.functions.UserDefinedFunction;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.LogicalTypeRoot;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.CloseableIterator;
import org.apache.flink.util.Collector;

import static org.apache.flink.table.api.Expressions.*;

import java.time.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

EnvironmentSettings settings = ConfluentSettings.fromGlobalVariables();
TableEnvironment env = TableEnvironment.create(settings);

System.out.println();
System.out.println();
System.out.println("Welcome to Apache Flink® Table API on Confluent Cloud");
System.out.println();
System.out.println();
System.out.println("A TableEnvironment has been pre-initialized and is available under `env`.");
System.out.println();
System.out.println("Some inspirations to get started:");
System.out.println("  - Say hello: env.executeSql(\"SELECT 'Hello world!'\").print();");
System.out.println("  - List catalogs: env.listCatalogs();");
System.out.println("  - Show something fancy: env.from(\"examples.marketplace.clicks\").execute().print();");
System.out.println();
System.out.println();
