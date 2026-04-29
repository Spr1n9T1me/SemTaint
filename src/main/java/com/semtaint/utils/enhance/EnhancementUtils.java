/*
 * Usage examples for Tai-e Code Enhancement Framework
 * Demonstrates how to use the framework for various code enhancement tasks
 */

package com.semtaint.utils.enhance;

import pascal.taie.Main;
import pascal.taie.World;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.VoidType;

import java.util.ArrayList;
import java.util.List;

/**
 * Examples demonstrating code enhancement capabilities
 */
public class EnhancementUtils {

    /**
     * Example 1: Create a new class with getter/setter methods
     */
    public static void createDataClass() {
        CodeEnhancer enhancer = new CodeEnhancer();

        // Get Object class as superclass
        JClass objectClass = World.get().getClassHierarchy()
            .getJREClass("java.lang.Object");

        // Create new class
        CodeEnhancer.EnhancedClass dataClass = enhancer.createClass(
            "com.example.GeneratedDataClass", objectClass);

        // Add a field
        JField nameField = enhancer.addField(
            dataClass.build(), "name",
            World.get().getTypeSystem().getClassType("java.lang.String"));

        // Create getter method
        JMethod getter = enhancer.addMethod(
            dataClass.build(), "getName",
            List.of(), nameField.getType());

        // Generate getter implementation
        StatementGenerator gen = new StatementGenerator(getter);
        List<Stmt> getterBody = new ArrayList<>();
        Var thisVar = gen.newLocal("this", dataClass.build().getType());
        Var result = gen.newLocal("result", nameField.getType());
        // this.name
        getterBody.add(gen.generateLoadField(result, thisVar, nameField));
        // return result
        getterBody.add(gen.generateReturn(result));
        // Set method IR
        getter.setIR(new CodeEnhancer().createMethodIR(getter, getterBody));
    }

    /**
     * Example 2: Inject logging into existing methods
     */
    public static void injectLogging(JMethod targetMethod) {
        CodeEnhancer enhancer = new CodeEnhancer();
        StatementGenerator gen = new StatementGenerator(targetMethod);

        // Create logging statements
        List<Stmt> loggingStmts = new ArrayList<>();

        // Get System.out
        JClass systemClass = World.get().getClassHierarchy()
            .getJREClass("java.lang.System");
        if (systemClass == null) return;
        JField outField = systemClass.getDeclaredField("out");
        if (outField == null) return;
        Var systemOut = gen.newLocal("systemOut", outField.getType());
        loggingStmts.add(gen.generateLoadField(systemOut, null, outField));

        // Create "Method entered" string
        Var messageVar = gen.newLocal("message",
            World.get().getTypeSystem().getClassType("java.lang.String"));
        loggingStmts.add(gen.generateAssignLiteral(messageVar,
            gen.stringLiteral("Method " + targetMethod.getName() + " entered")));

        // Call println
        JClass printStreamClass = World.get().getClassHierarchy()
            .getJREClass("java.io.PrintStream");
        if (printStreamClass == null) return;
        var printlnSubsig = pascal.taie.language.classes.Subsignature.get(
            "println", List.of(World.get().getTypeSystem().getClassType("java.lang.String")), VoidType.VOID);
        JMethod printlnMethod = printStreamClass.getDeclaredMethod(printlnSubsig);
        if (printlnMethod == null) return;

        loggingStmts.add(gen.generateInvokeVirtual(null, systemOut,
            printlnMethod, List.of(messageVar)));

        // Inject at method beginning
        enhancer.injectStatements(targetMethod, loggingStmts);
    }

    /**
     * Example 3: Create wrapper methods for existing functionality
     */
    public static void createWrapperMethod(JClass targetClass, JMethod originalMethod) {
        CodeEnhancer enhancer = new CodeEnhancer();

        // Create wrapper method
        JMethod wrapper = enhancer.addMethod(targetClass,
            "wrapped" + originalMethod.getName(),
            originalMethod.getParamTypes(),
            originalMethod.getReturnType());

        StatementGenerator gen = new StatementGenerator(wrapper);
        List<Stmt> wrapperBody = new ArrayList<>();

        // Pre-processing logic
        Var logVar = gen.newLocal("log",
            World.get().getTypeSystem().getClassType("java.lang.String"));
        wrapperBody.add(gen.generateAssignLiteral(logVar,
            gen.stringLiteral("Calling " + originalMethod.getName())));

        // Call original method
        Var thisVar = gen.newLocal("this", targetClass.getType());
        List<Var> args = new ArrayList<>();
        for (int i = 0; i < originalMethod.getParamCount(); i++) {
            args.add(gen.newLocal("param" + i, originalMethod.getParamType(i)));
        }

        if (!originalMethod.getReturnType().equals(VoidType.VOID)) {
            Var result = gen.newLocal("result", originalMethod.getReturnType());
            wrapperBody.add(gen.generateInvokeVirtual(result, thisVar, originalMethod, args));
            wrapperBody.add(gen.generateReturn(result));
        } else {
            wrapperBody.add(gen.generateInvokeVirtual(null, thisVar, originalMethod, args));
            wrapperBody.add(gen.generateVoidReturn());
        }
        wrapper.setIR(new CodeEnhancer().createMethodIR(wrapper, wrapperBody));
    }

    /**
     * Example 4: Add security checks to methods
     */
    public static void addSecurityCheck(JMethod method) {
        CodeEnhancer enhancer = new CodeEnhancer();
        StatementGenerator gen = new StatementGenerator(method);

        List<Stmt> securityStmts = new ArrayList<>();

        // Get SecurityManager
        JClass securityManagerClass = World.get().getClassHierarchy()
            .getJREClass("java.lang.SecurityManager");
        if (securityManagerClass == null) return;
        var checkPermSubsig = pascal.taie.language.classes.Subsignature.get(
            "checkPermission", List.of(World.get().getTypeSystem().getClassType("java.security.Permission")), VoidType.VOID);
        JMethod checkPermissionMethod = securityManagerClass.getDeclaredMethod(checkPermSubsig);
        if (checkPermissionMethod == null) return;

        // Create permission check
        Var permissionVar = gen.newLocal("permission", checkPermissionMethod.getParamType(0));
        // ... create permission object

        Var securityManagerVar = gen.newLocal("sm", securityManagerClass.getType());
        // ... get security manager

        securityStmts.add(gen.generateInvokeVirtual(null, securityManagerVar,
            checkPermissionMethod, List.of(permissionVar)));

        enhancer.injectStatements(method, securityStmts);
    }

    /**
     * Example 5: Create factory method
     */
    public static void createFactoryMethod(JClass targetClass) {
        CodeEnhancer enhancer = new CodeEnhancer();

        // Create static factory method
        JMethod factory = enhancer.addMethod(targetClass, "create",
            List.of(), targetClass.getType());

        StatementGenerator gen = new StatementGenerator(factory);
        List<Stmt> factoryBody = new ArrayList<>();

        // Create new instance
        Var instance = gen.newLocal("instance", targetClass.getType());
        factoryBody.add(gen.generateNew(instance, (pascal.taie.language.type.ClassType) targetClass.getType()));

        // Initialize if needed
        // ... initialization logic

        // Return instance
        factoryBody.add(gen.generateReturn(instance));

        factory.setIR(new CodeEnhancer().createMethodIR(factory, factoryBody));
    }

    /**
     * Example 6: Dynamically create an implementation class for an interface and implement its methods
     */
    public static JClass createInterfaceImpl(JClass interfaceClass) {
        CodeEnhancer enhancer = new CodeEnhancer();
        // Use Object as superclass
        JClass objectClass = World.get().getClassHierarchy().getJREClass("java.lang.Object");
        // Create new class that implements the interface
        CodeEnhancer.EnhancedClass implClass = enhancer.createClass(
            interfaceClass.getName() + "$$MockedImpl", objectClass);
        implClass.addInterface(interfaceClass);
        // For each method in the interface, create a concrete implementation
        for (JMethod interfaceMethod : interfaceClass.getDeclaredMethods()) {
            if (interfaceMethod.isStatic() || !interfaceMethod.isAbstract()) continue;
            enhancer.addMethod(
                implClass,
                interfaceMethod.getName(),
                interfaceMethod.getParamTypes(),
                interfaceMethod.getReturnType()
            );
        }
        JClass mockedClass = implClass.build();
        // build后再为每个方法生成IR
        for (JMethod implMethod : mockedClass.getDeclaredMethods()) {
            // 只为实现类中属于接口的抽象方法生成IR
            if (implMethod.isStatic() || !implMethod.isAbstract()) {
                StatementGenerator gen = new StatementGenerator(implMethod);
                List<Stmt> body = new ArrayList<>();
                if (!implMethod.getReturnType().equals(VoidType.VOID)) {
                    Var ret = gen.newLocal("ret", implMethod.getReturnType());
                    var type = implMethod.getReturnType();
                    if (type.toString().equals("java.lang.String")) {
                        body.add(gen.generateAssignLiteral(ret, gen.stringLiteral("implemented")));
                    } else if (type.toString().equals("int")) {
                        body.add(gen.generateAssignLiteral(ret, gen.intLiteral(0)));
                    } else {
                        body.add(gen.generateAssignLiteral(ret, gen.nullLiteral()));
                    }
                    body.add(gen.generateReturn(ret));
                } else {
                    body.add(gen.generateVoidReturn());
                }
                implMethod.setIR(new CodeEnhancer().createMethodIR(implMethod, body));
            }
        }
        return mockedClass;
    }

    // Helper: generate a println statement body
    private static List<Stmt> createPrintlnBody(StatementGenerator gen, JMethod method, String message) {
        List<Stmt> stmts = new ArrayList<>();
        JClass systemClass = World.get().getClassHierarchy().getJREClass("java.lang.System");
        if (systemClass == null) return stmts;
        JField outField = systemClass.getDeclaredField("out");
        if (outField == null) return stmts;
        Var systemOut = gen.newLocal("systemOut", outField.getType());
        stmts.add(gen.generateLoadField(systemOut, null, outField)); //TODO
        Var messageVar = gen.newLocal("message", World.get().getTypeSystem().getClassType("java.lang.String"));
        stmts.add(gen.generateAssignLiteral(messageVar, gen.stringLiteral(message)));
        JClass printStreamClass = World.get().getClassHierarchy().getJREClass("java.io.PrintStream");
        if (printStreamClass == null) return stmts;
        var printlnSubsig = pascal.taie.language.classes.Subsignature.get(
            "println", List.of(World.get().getTypeSystem().getClassType("java.lang.String")), VoidType.VOID);
        JMethod printlnMethod = printStreamClass.getDeclaredMethod(printlnSubsig);
        if (printlnMethod == null) return stmts;
        stmts.add(gen.generateInvokeVirtual(null, systemOut, printlnMethod, List.of(messageVar)));
        return stmts;
    }

    public static void main(String[] args) {
        Main.buildWorld("--options-file","src/main/resources/options.yml");
        JClass contextClz = World.get().getClassHierarchy().getClass("java.util.Collection");
        JClass enhancedClass = createInterfaceImpl(contextClz);
        System.out.println("methods: " + enhancedClass.getDeclaredMethods());
    }
}
