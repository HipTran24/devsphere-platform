package com.project.devsphere.operations.infrastructure.database;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;

/** Applies runtime grants using the migration connection after schema changes. */
@Component
@ConditionalOnProperty(name = "devsphere.database.manage-runtime-privileges", havingValue = "true", matchIfMissing = true)
public final class RuntimeRolePrivileges implements Callback {
    private final String runtimeUser;

    public RuntimeRolePrivileges(@Value("${spring.datasource.username}") String runtimeUser) {
        if (runtimeUser == null || !runtimeUser.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Runtime database role must be a lowercase SQL identifier");
        }
        this.runtimeUser = runtimeUser;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public String getCallbackName() {
        return "devsphere-runtime-privileges";
    }

    @Override
    public void handle(Event event, Context context) {
        apply(context.getConnection());
    }

    public void apply(Connection connection) {
        try {
            if (runtimeUser.equals(connection.getMetaData().getUserName())) {
                throw new FlywayException("Runtime database role must differ from the migration owner");
            }
            try (var query = connection.prepareStatement("select rolsuper,rolcreatedb,rolcreaterole,rolreplication from pg_roles where rolname=?")) {
                query.setString(1, runtimeUser);
                try (var role = query.executeQuery()) {
                    if (!role.next()) {
                        throw new FlywayException("Runtime role is missing; run docker compose run --rm database-setup first");
                    }
                    if (role.getBoolean(1) || role.getBoolean(2) || role.getBoolean(3) || role.getBoolean(4)) {
                        throw new FlywayException("Runtime database role has administrative privileges");
                    }
                }
            }
            String role = '"' + runtimeUser + '"';
            try (var sql = connection.createStatement()) {
                sql.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
                sql.execute("GRANT USAGE ON SCHEMA public TO " + role);
                sql.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO " + role);
                sql.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO " + role);
                sql.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO " + role);
                sql.execute("REVOKE UPDATE,DELETE ON audit_logs,schedule_versions,schedule_entries FROM " + role);
                sql.execute("REVOKE ALL ON flyway_schema_history FROM " + role);
            }
        } catch (SQLException error) {
            throw new FlywayException("Cannot apply runtime database privileges", error);
        }
    }
}
