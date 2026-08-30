package com.offcanon.infrastructure.sqlite;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Wire-format helpers shared by all SQLite repositories.
 *
 * <p>SQLite has no timestamp type.  We store instants as signed epoch
 * microseconds in INTEGER columns, matching the precision of the former
 * TIMESTAMP(6) contract without losing sub-millisecond ordering.</p>
 */
final class SqliteValues {
    private static final long MICROS_PER_SECOND = 1_000_000L;

    private SqliteValues() {
    }

    static long epochMicros(Instant value) {
        if (value == null) throw new IllegalArgumentException("Instant must not be null");
        return Math.addExact(Math.multiplyExact(value.getEpochSecond(), MICROS_PER_SECOND),
                value.getNano() / 1_000L);
    }

    static Instant instant(long epochMicros) {
        long seconds = Math.floorDiv(epochMicros, MICROS_PER_SECOND);
        long micros = Math.floorMod(epochMicros, MICROS_PER_SECOND);
        return Instant.ofEpochSecond(seconds, micros * 1_000L);
    }

    static Instant instant(ResultSet result, String column) throws SQLException {
        return instant(result.getLong(column));
    }
}
