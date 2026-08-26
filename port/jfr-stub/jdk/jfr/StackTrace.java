package jdk.jfr;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface StackTrace {
    boolean value() default true;
}
