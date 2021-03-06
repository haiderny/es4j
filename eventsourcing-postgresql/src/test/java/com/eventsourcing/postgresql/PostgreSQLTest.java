/**
 * Copyright (c) 2016, All Contributors (see CONTRIBUTORS file)
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.eventsourcing.postgresql;

import com.impossibl.postgres.jdbc.PGDataSource;
import lombok.SneakyThrows;
import ru.yandex.qatools.embed.postgresql.PostgresExecutable;
import ru.yandex.qatools.embed.postgresql.PostgresProcess;
import ru.yandex.qatools.embed.postgresql.PostgresStarter;
import ru.yandex.qatools.embed.postgresql.config.PostgresConfig;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

public abstract class PostgreSQLTest {

    private static PostgresConfig pgConfig;

    @SneakyThrows
    public static PGDataSource createDataSource() {
        if (pgConfig == null) {
            pgConfig = launchPostgres();
        }

        PGDataSource ds = new PGDataSource();
        ds.setHost(pgConfig.net().host());
        ds.setDatabase(pgConfig.storage().dbName());
        ds.setUser(pgConfig.credentials().username());
        ds.setPassword(pgConfig.credentials().password());
        ds.setPort(pgConfig.net().port());
        ds.setHousekeeper(false);


        String databaseName = UUID.randomUUID().toString();

        try (Connection c = ds.getConnection()) {
            try (PreparedStatement preparedStatement = c.prepareStatement("CREATE DATABASE \"" + databaseName + "\"")) {
                preparedStatement.executeUpdate();
            }
        }

        ds.setDatabase(databaseName);

        return ds;
    }

    protected static PostgresConfig launchPostgres() throws IOException {
        PostgresStarter<PostgresExecutable, PostgresProcess> runtime = PostgresStarter.getDefaultInstance();
        final PostgresConfig pgConfig = PostgresConfig.defaultWithDbName("eventsourcing",
                                                                         "eventsourcing", "eventsourcing");
        PostgresExecutable exec = runtime.prepare(pgConfig);
        PostgresProcess process = exec.start();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override public void run() {
                process.stop();
            }
        });

        return pgConfig;
    }

}
