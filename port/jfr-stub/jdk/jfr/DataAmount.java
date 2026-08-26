package jdk.jfr;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface DataAmount {
    String BYTES = "BYTES";
    String BITS = "BITS";
    String value() default BYTES;
}
