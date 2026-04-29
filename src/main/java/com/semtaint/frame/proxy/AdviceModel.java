package com.semtaint.frame.proxy;


import pascal.taie.language.classes.JMethod;

// class for AOP advice annotation like @Before, @After, @Around, @AfterReturning, @AfterThrowing
public record AdviceModel(JMethod insertMethod, AdviceEnum adviceType, String pointcutExpression) {
    public enum AdviceEnum {
        AROUND,
        BEFORE,
        AFTER_RETURNING,
        AFTER_THROWING,
        AFTER;
    }
}

/**
 * package com.example.demo.aspect;
 *
 * import org.aspectj.lang.JoinPoint;
 * import org.aspectj.lang.ProceedingJoinPoint;
 * import org.aspectj.lang.annotation.After;
 * import org.aspectj.lang.annotation.AfterReturning;
 * import org.aspectj.lang.annotation.Around;
 * import org.aspectj.lang.annotation.Aspect;
 * import org.aspectj.lang.annotation.Before;
 * import org.aspectj.lang.annotation.Pointcut;
 * import org.slf4j.Logger;
 * import org.slf4j.LoggerFactory;
 * import org.springframework.core.annotation.Order;
 * import org.springframework.stereotype.Component;
 *
 * @Aspect
 * @Order(1)
 * @Component
 * public class AOPAspectA {
 *     private static final Logger LOGGER = LoggerFactory.getLogger(AOPAspectA.class);
 *
 *     public AOPAspectA() {
 *     }
 *
 *     @Pointcut("execution(public * com.example.demo.service.AOPService.testAOPAnno*(..))")
 *     public void webLog1() {
 *     }
 *
 *     @Around("webLog1()")
 *     public Object aroundMethod(ProceedingJoinPoint pdj) {
 *         System.out.println("AOPAspectA @Around2 Before");
 *         Object result = null;
 *
 *         try {
 *             result = pdj.proceed();
 *         } catch (Throwable var4) {
 *             var4.printStackTrace();
 *         }
 *
 *         System.out.println("AOPAspectA @Around2 After");
 *         return result;
 *     }
 *
 *     @Before("webLog1()")
 *     public void doBefore(JoinPoint joinPoint) {
 *         System.out.println("AOPAspectA @Before");
 *     }
 *
 *     @After("webLog1()")
 *     public void doAfter(JoinPoint joinPoint) {
 *         System.out.println("AOPAspectA @After");
 *     }
 *
 *     @AfterReturning(
 *         value = "webLog1()",
 *         returning = "result"
 *     )
 *     public void afterReturning(JoinPoint point, Object result) {
 *         System.out.println("AOPAspectA @AfterReturning");
 *     }
 * }
 */
