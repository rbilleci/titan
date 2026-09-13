package io.titan.runtime.testing;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TitanTestAnnotationTest {

    @Test
    void annotationContractIsRuntimeTypeLevel() {
        Retention retention = TitanTest.class.getAnnotation(Retention.class);
        Target target = TitanTest.class.getAnnotation(Target.class);

        assertEquals(RetentionPolicy.RUNTIME, retention.value());
        assertArrayEquals(new ElementType[]{ElementType.TYPE}, target.value());
    }

    @Test
    void defaultsToDualDialectTargets() throws NoSuchMethodException {
        DatabaseTarget[] defaultTargets = (DatabaseTarget[]) TitanTest.class
                .getMethod("targets")
                .getDefaultValue();

        assertArrayEquals(new DatabaseTarget[]{DatabaseTarget.POSTGRESQL, DatabaseTarget.MYSQL}, defaultTargets);
    }
}
