package io.titan.transpiler.tir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PostgreSqlTypeMapperTest {

    /**
     * Regression test for the snake-casing drift fixed by plan 1.3a (audit S2): the type mapper's
     * private toSnakeCase copy lacked the emitters' non-alphanumeric cleanup, so a record named
     * {@code My$Record} produced {@code app.__record_my$_record} via the type mapper but
     * {@code app.__record_my_record} via the emitter. Both paths must agree.
     */
    @Test
    void recordTypeNameSnakeCasingMatchesEmitterForNonAlphanumericNames() {
        TRecordType recordType = new TRecordType("app", "My$Record");

        String mapperSqlType = new PostgreSqlTypeMapper().toSqlType(recordType);
        String emitterSqlType = new PostgreSqlEmitter().sqlType(recordType);

        assertEquals("\"app\".\"__record_my_record\"", mapperSqlType);
        assertEquals(emitterSqlType, mapperSqlType);
    }

    /**
     * UUID lowers to PostgreSQL's native {@code UUID} and to MySQL's {@code CHAR(36)} (no native UUID
     * type; canonical 36-char string per charter §1.1a). Mapper and emitter must agree on both dialects.
     */
    @Test
    void uuidMapsToNativeOnPostgresAndCharThirtySixOnMysql() {
        TUuidType uuid = new TUuidType();

        assertEquals("UUID", new PostgreSqlTypeMapper().toSqlType(uuid));
        assertEquals("UUID", new PostgreSqlEmitter().sqlType(uuid));
        assertEquals("CHAR(36)", new MySqlTypeMapper().toSqlType(uuid));
        assertEquals("CHAR(36)", new MySqlEmitter().sqlType(uuid));

        // List<UUID>: native uuid[] on PG; the whole list binds as one JSON param on MySQL.
        TArrayType uuidArray = new TArrayType(uuid);
        assertEquals("UUID[]", new PostgreSqlEmitter().sqlType(uuidArray));
        assertEquals("JSON", new MySqlEmitter().sqlType(uuidArray));
    }

    /**
     * ATG-002b: a {@code byte[]} (TBytesType) is an opaque binary payload — PostgreSQL {@code BYTEA},
     * MySQL {@code LONGBLOB} — not {@code INTEGER[]}. Mapper and emitter must agree on both dialects.
     */
    @Test
    void bytesMapToByteaOnPostgresAndLongblobOnMysql() {
        TBytesType bytes = new TBytesType();

        assertEquals("BYTEA", new PostgreSqlTypeMapper().toSqlType(bytes));
        assertEquals("BYTEA", new PostgreSqlEmitter().sqlType(bytes));
        assertEquals("LONGBLOB", new MySqlTypeMapper().toSqlType(bytes));
        assertEquals("LONGBLOB", new MySqlEmitter().sqlType(bytes));
    }
}
