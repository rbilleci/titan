package io.titan.runtime.testing;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(TitanTestExtension.class)
public @interface TitanTest {
    DatabaseTarget[] targets() default {DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL};

    /**
     * Optional schema SQL statements/scripts applied to each test's freshly provisioned
     * database before the test runs. Statements may include multiple semicolon-separated
     * commands.
     */
    String[] schemaSql() default {};
}
