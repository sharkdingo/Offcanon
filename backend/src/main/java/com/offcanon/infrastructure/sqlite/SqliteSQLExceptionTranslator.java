package com.offcanon.infrastructure.sqlite;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.support.AbstractFallbackSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;

import java.sql.SQLException;
import java.util.Locale;

/**
 * SQLite reports all constraint failures with vendor error code 19 and no
 * SQLState.  Spring therefore classifies UNIQUE/PRIMARY KEY violations as
 * UncategorizedSQLException unless the driver-specific translation is
 * supplied explicitly.
 */
final class SqliteSQLExceptionTranslator extends AbstractFallbackSQLExceptionTranslator {
    SqliteSQLExceptionTranslator() {
        setFallbackTranslator(new SQLExceptionSubclassTranslator());
    }

    @Override
    protected DataAccessException doTranslate(String task, String sql, SQLException error) {
        SQLException constraint = findConstraint(error);
        if (constraint == null) return null;

        String message = String.valueOf(constraint.getMessage()).toUpperCase(Locale.ROOT);
        String detail = buildMessage(task, sql, constraint);
        if (message.contains("CONSTRAINT_UNIQUE")
                || message.contains("CONSTRAINT_PRIMARYKEY")
                || message.contains("UNIQUE CONSTRAINT FAILED")
                || message.contains("PRIMARY KEY CONSTRAINT FAILED")) {
            return new DuplicateKeyException(detail, constraint);
        }
        return new DataIntegrityViolationException(detail, constraint);
    }

    private SQLException findConstraint(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            if (current.getErrorCode() == 19
                    || String.valueOf(current.getMessage()).toUpperCase(Locale.ROOT).contains("SQLITE_CONSTRAINT")) {
                return current;
            }
            Throwable cause = current.getCause();
            if (cause instanceof SQLException nested) {
                SQLException found = findConstraint(nested);
                if (found != null) return found;
            }
        }
        return null;
    }
}
