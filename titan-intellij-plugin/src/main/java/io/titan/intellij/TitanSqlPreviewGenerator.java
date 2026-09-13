package io.titan.intellij;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class TitanSqlPreviewGenerator {

    private static final String STORED_PROCEDURE_FQN = "titan.dsl.StoredProcedure";
    private static final String STORED_FUNCTION_FQN = "titan.dsl.StoredFunction";
    private static final String TRIGGER_FQN = "titan.dsl.Trigger";
    private static final String SCHEDULED_JOB_FQN = "titan.dsl.ScheduledJob";
    private static final String SECURITY_DEFINER_FQN = "titan.dsl.SecurityDefiner";

    private TitanSqlPreviewGenerator() {
    }

    public static boolean supports(@NotNull PsiMethod method) {
        return method.getAnnotation(STORED_PROCEDURE_FQN) != null
                || method.getAnnotation(STORED_FUNCTION_FQN) != null
                || method.getAnnotation(TRIGGER_FQN) != null
                || method.getAnnotation(SCHEDULED_JOB_FQN) != null;
    }

    public static @NotNull SqlPreview generate(@NotNull PsiMethod method) {
        PsiAnnotation trigger = method.getAnnotation(TRIGGER_FQN);
        if (trigger != null) {
            return buildTriggerPreview(method, trigger);
        }

        PsiAnnotation scheduledJob = method.getAnnotation(SCHEDULED_JOB_FQN);
        if (scheduledJob != null) {
            return buildScheduledJobPreview(method, scheduledJob);
        }

        boolean procedure = method.getAnnotation(STORED_PROCEDURE_FQN) != null;
        boolean securityDefiner = method.getAnnotation(SECURITY_DEFINER_FQN) != null;
        String routineName = method.getName();
        String params = Arrays.stream(method.getParameterList().getParameters())
                .map(TitanSqlPreviewGenerator::toParameterSql)
                .collect(Collectors.joining(",\n    "));

        String pg = buildPostgres(routineName, params, procedure, securityDefiner);
        String my = buildMySql(routineName, params, procedure, securityDefiner);
        return new SqlPreview(pg, my);
    }

    private static String buildPostgres(String name, String params, boolean procedure, boolean securityDefiner) {
        String header = procedure
                ? "CREATE OR REPLACE PROCEDURE " + name + "(\n"
                : "CREATE OR REPLACE FUNCTION " + name + "(\n";
        String returnClause = procedure ? "" : ")\nRETURNS TEXT\n";
        String bodyReturn = procedure ? "" : "    RETURN ''::text;\n";
        String securityClause = securityDefiner ? "SECURITY DEFINER\n" : "SECURITY INVOKER\n";
        return header
                + params + "\n)\n"
                + returnClause
                + "LANGUAGE plpgsql\n"
                + securityClause
                + "AS $$\n"
                + "BEGIN\n"
                + bodyReturn
                + "END;\n"
                + "$$;";
    }

    private static String buildMySql(String name, String params, boolean procedure, boolean securityDefiner) {
        String dropStatement = procedure
                ? "DROP PROCEDURE IF EXISTS " + name + "$$\n"
                : "DROP FUNCTION IF EXISTS " + name + "$$\n";
        String header = procedure
                ? "CREATE PROCEDURE " + name + "(\n"
                : "CREATE FUNCTION " + name + "(\n";
        String returnClause = procedure ? "" : ")\nRETURNS TEXT\n";
        String bodyReturn = procedure ? "" : "  RETURN '';\n";
        String securityClause = securityDefiner ? "SQL SECURITY DEFINER\n" : "SQL SECURITY INVOKER\n";
        return "DELIMITER $$\n"
                + dropStatement
                + header
                + params + "\n)\n"
                + returnClause
                + securityClause
                + "BEGIN\n"
                + bodyReturn
                + "END$$\n"
                + "DELIMITER ;";
    }

    private static SqlPreview buildTriggerPreview(PsiMethod method, PsiAnnotation trigger) {
        String triggerName = method.getName() + "_trg";
        String table = annotationStringValue(trigger, "table", "<table>");
        String timing = annotationEnumValue(trigger, "timing", "BEFORE");
        List<String> events = annotationEnumArrayValues(trigger, "event", List.of("INSERT"));

        String pgEvents = String.join(" OR ", events);
        String pgReturn = events.size() == 1 && "DELETE".equals(events.get(0)) ? "OLD" : "NEW";
        String pg = "CREATE OR REPLACE FUNCTION " + method.getName() + "() RETURNS TRIGGER AS $$\n"
                + "BEGIN\n"
                + "  -- Generated trigger body preview\n"
                + "  RETURN " + pgReturn + ";\n"
                + "END;\n"
                + "$$ LANGUAGE plpgsql;\n\n"
                + "CREATE TRIGGER " + triggerName + "\n"
                + "  " + timing + " " + pgEvents + " ON " + table + "\n"
                + "  FOR EACH ROW\n"
                + "  EXECUTE FUNCTION " + method.getName() + "();";

        String my = events.stream()
                .map(event -> "DELIMITER $$\n"
                        + "CREATE TRIGGER " + triggerName + "_" + event.toLowerCase(Locale.ROOT) + " "
                        + timing + " " + event + " ON " + table + "\n"
                        + "FOR EACH ROW\n"
                        + "BEGIN\n"
                        + "  -- Generated trigger body preview\n"
                        + "END$$\n"
                        + "DELIMITER ;")
                .collect(Collectors.joining("\n\n"));

        return new SqlPreview(pg, my);
    }

    private static SqlPreview buildScheduledJobPreview(PsiMethod method, PsiAnnotation scheduledJob) {
        String cron = annotationStringValue(scheduledJob, "cron", "* * * * *");
        String jobName = annotationStringValue(scheduledJob, "name", method.getName() + "_job");

        String pg = "CREATE OR REPLACE PROCEDURE " + method.getName() + "()\n"
                + "LANGUAGE plpgsql AS $$\n"
                + "BEGIN\n"
                + "  -- Generated scheduled job body preview\n"
                + "END;\n"
                + "$$;\n\n"
                + "DO $$\n"
                + "BEGIN\n"
                + "  IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN\n"
                + "    PERFORM cron.schedule('" + jobName + "', '" + cron + "', $$CALL " + method.getName() + "();$$);\n"
                + "  ELSE\n"
                + "    RAISE WARNING 'pg_cron extension not installed; scheduled job " + jobName + " was not registered';\n"
                + "  END IF;\n"
                + "END $$;";

        MySqlEventSchedule schedule = mysqlScheduleFromCron(cron);
        String warningComment = schedule.warningComment() == null
                ? ""
                : "-- NOTE: " + schedule.warningComment() + "\n";

        String my = "CREATE PROCEDURE " + method.getName() + "()\n"
                + "BEGIN\n"
                + "  -- Generated scheduled job body preview\n"
                + "END;\n\n"
                + "DROP EVENT IF EXISTS " + jobName + ";\n"
                + warningComment
                + "CREATE EVENT " + jobName + "\n"
                + "  ON SCHEDULE EVERY " + schedule.everyExpression() + " STARTS " + schedule.startsExpression() + "\n"
                + "  DO CALL " + method.getName() + "();";

        return new SqlPreview(pg, my);
    }

    private static MySqlEventSchedule mysqlScheduleFromCron(String cron) {
        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 5) {
            return new MySqlEventSchedule("1 DAY", "CURRENT_TIMESTAMP", "cron expression is not directly representable by MySQL events; using daily fallback");
        }
        String minute = parts[0];
        String hour = parts[1];
        String dayOfMonth = parts[2];
        String month = parts[3];
        String dayOfWeek = parts[4];

        if ("*".equals(minute) && "*".equals(hour) && "*".equals(dayOfMonth)
                && "*".equals(month) && "*".equals(dayOfWeek)) {
            return new MySqlEventSchedule("1 MINUTE", "CURRENT_TIMESTAMP", null);
        }
        if (!"*".equals(minute) && "*".equals(hour) && "*".equals(dayOfMonth)
                && "*".equals(month) && "*".equals(dayOfWeek)) {
            return new MySqlEventSchedule("1 HOUR", "CURRENT_DATE + INTERVAL " + minute + " MINUTE", null);
        }
        if (!"*".equals(minute) && !"*".equals(hour) && "*".equals(dayOfMonth)
                && "*".equals(month) && "*".equals(dayOfWeek)) {
            return new MySqlEventSchedule("1 DAY", "CURRENT_DATE + INTERVAL " + hour + " HOUR + INTERVAL " + minute + " MINUTE", null);
        }
        if (!"*".equals(minute) && !"*".equals(hour) && dayOfMonth.matches("\\d+")
                && "*".equals(month) && "*".equals(dayOfWeek)) {
            return new MySqlEventSchedule("1 MONTH", "CURRENT_DATE + INTERVAL " + (Integer.parseInt(dayOfMonth) - 1) + " DAY + INTERVAL " + hour + " HOUR + INTERVAL " + minute + " MINUTE", null);
        }
        return new MySqlEventSchedule("1 DAY", "CURRENT_TIMESTAMP", "cron expression is not directly representable by MySQL events; using daily fallback");
    }

    private record MySqlEventSchedule(String everyExpression, String startsExpression, String warningComment) {}

    private static String annotationStringValue(PsiAnnotation annotation, String attribute, String fallback) {
        var value = annotation.findAttributeValue(attribute);
        if (value == null) {
            return fallback;
        }
        String raw = value.getText();
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static String annotationEnumValue(PsiAnnotation annotation, String attribute, String fallback) {
        var value = annotation.findAttributeValue(attribute);
        if (value == null) {
            return fallback;
        }
        return enumTail(value.getText(), fallback);
    }

    private static List<String> annotationEnumArrayValues(PsiAnnotation annotation, String attribute, List<String> fallback) {
        var value = annotation.findAttributeValue(attribute);
        if (value == null) {
            return fallback;
        }
        String raw = value.getText().trim();
        if (raw.startsWith("{") && raw.endsWith("}")) {
            raw = raw.substring(1, raw.length() - 1).trim();
            if (raw.isBlank()) {
                return fallback;
            }
            return Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .map(token -> enumTail(token, null))
                    .filter(token -> token != null && !token.isBlank())
                    .toList();
        }
        String single = enumTail(raw, fallback.isEmpty() ? null : fallback.get(0));
        return single == null || single.isBlank() ? fallback : List.of(single);
    }

    private static String enumTail(String token, String fallback) {
        String normalized = token.trim();
        int dot = normalized.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < normalized.length()) {
            return normalized.substring(dot + 1).trim();
        }
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String toParameterSql(PsiParameter parameter) {
        return "IN p_" + parameter.getName() + " " + mapType(parameter.getType().getPresentableText());
    }

    private static String mapType(String javaType) {
        String normalized = javaType.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "int", "integer" -> "INT";
            case "long" -> "BIGINT";
            case "double" -> "DOUBLE";
            case "float" -> "FLOAT";
            case "boolean" -> "BOOLEAN";
            default -> "TEXT";
        };
    }

    public record SqlPreview(String postgresql, String mysql) {
    }
}
