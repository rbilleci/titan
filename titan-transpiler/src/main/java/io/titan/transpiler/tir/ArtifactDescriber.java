package io.titan.transpiler.tir;

import java.util.List;

/**
 * Describes the SQL objects an emitter creates for each artifact kind (plan 4.4, audit G-10).
 *
 * <p>Implemented by the dialect emitters themselves so the described names, parameter types and
 * trigger shapes come from exactly the code that renders the SQL (PostgreSQL triggers are a
 * trigger function plus a {@code CREATE TRIGGER}; MySQL triggers are one trigger per event;
 * MySQL scheduled jobs add a {@code CREATE EVENT}). The packager consumes these descriptors —
 * the emitted SQL text is never re-parsed.</p>
 */
public interface ArtifactDescriber {

    List<SqlObject> describeProcedure(String schema, String name, List<RoutineParameter> parameters);

    List<SqlObject> describeFunction(String schema, String name, TirType returnType, List<RoutineParameter> parameters);

    List<SqlObject> describeTrigger(String schema, String name, TriggerSpec trigger);

    List<SqlObject> describeScheduledJob(String schema, String name, ScheduledJobSpec scheduledJob, List<RoutineParameter> parameters);

    default List<SqlObject> describeView(String schema, String name) {
        return List.of(SqlObject.of(SqlObject.Kind.VIEW, schema, name));
    }

    List<SqlObject> describeEnumLookup(String schema, EnumLookupSpec enumLookupSpec);

    List<SqlObject> describeRecordModel(String schema, RecordModelSpec recordModelSpec);
}
