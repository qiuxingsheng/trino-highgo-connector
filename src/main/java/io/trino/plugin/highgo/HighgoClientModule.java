/*
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
package io.trino.plugin.highgo;

import com.google.inject.Binder;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.airlift.log.Logger;
import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.ConnectionFactory;
import io.trino.plugin.jdbc.DecimalModule;
import io.trino.plugin.jdbc.DriverConnectionFactory;
import io.trino.plugin.jdbc.ForBaseJdbc;
import io.trino.plugin.jdbc.JdbcClient;
import io.trino.plugin.jdbc.JdbcJoinPushdownSupportModule;
import io.trino.plugin.jdbc.JdbcStatisticsConfig;
import io.trino.plugin.jdbc.QueryBuilder;
import io.trino.plugin.jdbc.RemoteQueryCancellationModule;
import io.trino.plugin.jdbc.credential.CredentialProvider;
import io.trino.plugin.jdbc.ptf.Query;
import io.trino.spi.ptf.ConnectorTableFunction;
import com.highgo.jdbc.Driver;

import java.sql.DriverManager;
import java.util.Properties;

import static com.google.inject.multibindings.Multibinder.newSetBinder;
import static com.google.inject.multibindings.OptionalBinder.newOptionalBinder;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.trino.plugin.jdbc.JdbcModule.bindSessionPropertiesProvider;

public class HighgoClientModule
        extends AbstractConfigurationAwareModule
{
    private static final Logger log = Logger.get(HighgoClientModule.class);

    static {
        try {
            // 显式注册 HighGo 驱动到 DriverManager
            Driver highgoDriver = new Driver();
            DriverManager.registerDriver(highgoDriver);
            log.info("HighGo JDBC driver registered successfully: %s", highgoDriver.getClass().getName());

            // 验证驱动是否接受 jdbc:highgo:// 协议
            if (highgoDriver.acceptsURL("jdbc:highgo://localhost:5866/test")) {
                log.info("HighGo driver accepts jdbc:highgo:// protocol");
            }
            else {
                log.warn("HighGo driver does NOT accept jdbc:highgo:// protocol - this may cause connection issues");
            }
        }
        catch (Exception e) {
            log.error(e, "Failed to register HighGo JDBC driver");
            throw new RuntimeException("Cannot initialize HighGo JDBC driver", e);
        }
    }
    @Override
    public void setup(Binder binder)
    {
        binder.bind(JdbcClient.class).annotatedWith(ForBaseJdbc.class).to(HighgoClient.class).in(Scopes.SINGLETON);
        configBinder(binder).bindConfig(HighgoConfig.class);
        configBinder(binder).bindConfig(JdbcStatisticsConfig.class);
        bindSessionPropertiesProvider(binder, HighgoSessionProperties.class);
        newOptionalBinder(binder, QueryBuilder.class).setBinding().to(CollationAwareQueryBuilder.class).in(Scopes.SINGLETON);
        install(new DecimalModule());
        install(new JdbcJoinPushdownSupportModule());
        install(new RemoteQueryCancellationModule());
        newSetBinder(binder, ConnectorTableFunction.class).addBinding().toProvider(Query.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    @ForBaseJdbc
    public ConnectionFactory getConnectionFactory(BaseJdbcConfig config, CredentialProvider credentialProvider)
    {
        Properties connectionProperties = new Properties();
        connectionProperties.put("rewriteBatchedInserts", "true");
        connectionProperties.put("loginTimeout", "60");
        connectionProperties.put("connectTimeout", "60");
        connectionProperties.put("socketTimeout", "300");
//        connectionProperties.put("protocolVersion", "3.0");

        String connectionUrl = config.getConnectionUrl();
        log.info("Initializing HighGo connection factory with URL: %s", connectionUrl);

        try {
            Driver driver = new Driver();
            log.info("Created HighGo driver instance: %s", driver.getClass().getName());
            return new DriverConnectionFactory(driver, connectionUrl, connectionProperties, credentialProvider);
        }
        catch (Exception e) {
            log.error(e, "Failed to create HighGo driver instance");
            throw new RuntimeException("Cannot create HighGo driver instance", e);
        }
    }
}
