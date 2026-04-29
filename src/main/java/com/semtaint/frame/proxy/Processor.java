package com.semtaint.frame.proxy;


import com.semtaint.config.ConfManager;
import com.semtaint.frame.proxy.loader.ClassLoaderUtil;
import com.semtaint.utils.TypeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.aspectj.weaver.tools.PointcutExpression;
import org.aspectj.weaver.tools.PointcutParser;
import org.aspectj.weaver.tools.PointcutPrimitive;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Sets;

import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.*;
/**
 * @description: Processor for AOP point-cut expression
 * @author: springtime
 **/
public class Processor {
    /*
        - complex expr: @Pointcut("within(com.example.service.*) && @annotation(org.springframework.transaction.annotation.Transactional)")
        - fuzzy matcher: @Pointcut("execution(* com.example.service.*.*(..))")
    */
    private static final Logger logger = LogManager.getLogger(Processor.class);
    public List<Method> allAppMethods = new ArrayList<>();
    public PointcutParser parser;
    public ClassLoader preLoader;

    public Processor() {
        init();
    }
    public void process(AOPClassModel model) {
        // JoinPoint with alias
        model.getPointCutRawValue().forEach((key, value) -> {
            Set<JMethod> targetMethods = Sets.newSet();
            PointcutExpression pointcutExpression = parser.parsePointcutExpression(value);
            logger.info("[*]Pointcut expression: " + pointcutExpression.getPointcutExpression());
            allAppMethods.forEach(method -> {
                if (pointcutExpression.matchesMethodExecution(method).alwaysMatches()) {
                    JMethod jMethod = TypeUtils.buildJMethod(method);
                    if (jMethod != null)
                        targetMethods.add(jMethod);
                    logger.info("    [+]Matched method: " + method.getDeclaringClass().getTypeName() + "#" + method.getName());
                }
            });
            if (targetMethods.isEmpty())
                logger.info("    [-]No matched method for pointcut expression: " + value);
            model.getTargetMethods().put(key, targetMethods);
        });
        // JoinPoint without alias
        List<String> pointCutAlias = model.getPointCutAlias();
        model.getAdvices().forEach(advice ->{
            Set<JMethod> targetMethods = Sets.newSet();
            String adviceExpr = advice.pointcutExpression();
            if (pointCutAlias.stream().noneMatch(adviceExpr::contains)){ // "weblog1()" contains weblog1
                try {
                    PointcutExpression pointcutExpression = parser.parsePointcutExpression(adviceExpr);
                    logger.info("[*]Pointcut expression: " + pointcutExpression.getPointcutExpression());
                    allAppMethods.forEach(method -> {
                        if (pointcutExpression.matchesMethodExecution(method).alwaysMatches()) {
                            JMethod jMethod = TypeUtils.buildJMethod(method);
                            if (jMethod != null)
                                targetMethods.add(jMethod);
                            logger.info("   [+]Matched method: " + method.getDeclaringClass().getTypeName() + "#" + method.getName());
                        }
                    });
                }catch (IllegalArgumentException e){
                    logger.error(e.getMessage());
                }
                if (targetMethods.isEmpty())
                    logger.info("   [-]No matched method for pointcut expression: " + adviceExpr);
                model.getTargetMethods().put(advice.insertMethod(), targetMethods);
            }
        });
    }
    public void init() {
        Thread aopLoaderThread = new Thread(() -> {
            try {
                preLoader = ClassLoader.getSystemClassLoader();
                // 创建PointcutParser
                HashSet<PointcutPrimitive> supportedPrimitives = new HashSet<>(Arrays.asList(
                        PointcutPrimitive.EXECUTION,
                        PointcutPrimitive.CALL,
                        PointcutPrimitive.GET,
                        PointcutPrimitive.SET,
                        PointcutPrimitive.HANDLER,
                        PointcutPrimitive.INITIALIZATION,
                        PointcutPrimitive.PRE_INITIALIZATION,
                        PointcutPrimitive.STATIC_INITIALIZATION,
                        PointcutPrimitive.ADVICE_EXECUTION,
                        PointcutPrimitive.WITHIN,
                        PointcutPrimitive.WITHIN_CODE,
                        PointcutPrimitive.AT_ANNOTATION,
                        PointcutPrimitive.AT_WITHIN,
                        PointcutPrimitive.AT_ARGS,
                        PointcutPrimitive.AT_TARGET
                ));

//                this.parser = PointcutParser.getPointcutParserSupportingSpecifiedPrimitivesAndUsingContextClassloaderForResolution(supportedPrimitives);
                /**
                 * first for lib dir(.jar) , second for class dir(.class)
                 */
                ConfManager config = ConfManager.v();
                List<String> paths = List.of(config.getString("path.app-lib-path", ""), config.getString("path.app-class-path", ""));
//                ClassLoaderUtil.rootDir = paths.get(1);
                URLClassLoader tempAOPLoader = ClassLoaderUtil.addURLToClasspath(paths);
                this.parser = PointcutParser.getPointcutParserSupportingAllPrimitivesAndUsingSpecifiedClassloaderForResolution(tempAOPLoader);
//                Thread.currentThread().setContextClassLoader(tempLoader);
                Set<Method> methods = ClassLoaderUtil.loadMethodsFromURLClassLoader(tempAOPLoader);

                methods.stream().filter(method -> method.getDeclaringClass().getName().startsWith(config.getString("app.package-name", ""))).forEach(method -> {
                    allAppMethods.add(method);
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        aopLoaderThread.start();
        try {
            aopLoaderThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

    }

    public void destroy() {
        Thread.currentThread().setContextClassLoader(preLoader);
    }

    public static void main(String[] args) {
//        new Processor("execution(public * org.owasp.webgoat.container.session.Course.get*())").process();
    }
}

