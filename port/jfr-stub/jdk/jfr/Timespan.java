package jdk.jfr;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface Timespan {
    String MILLISECONDS = "MILLISECONDS";
    String NANOSECONDS = "NANOSECONDS";
    String TICKS = "TICKS";
    String SECONDS = "SECONDS";
    String value() default TICKS;
}
