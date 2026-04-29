package com.semtaint.utils;

import com.semtaint.config.ConfManager;
import pascal.taie.World;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ArrayType;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.PrimitiveType;
import pascal.taie.language.type.Type;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @description:
 * @author: springtime
 **/
public class TypeUtils {

    /**
     * Check if the given class is a POJO class.
     *
     * @param jClass the class to check
     * @return true if the class is a POJO class, false otherwise
     */
    public static boolean isPOJOClass(JClass jClass) {
        // Check if the class is not an interface or abstract
        if (jClass.isInterface() || jClass.isAbstract()) {
            return false;
        }

        // Check if the class is a part of the application package
        String className = jClass.getName();
        String packageName = ConfManager.v().getString("app.package-name", "");
        if (!className.startsWith(packageName)) {
            return false;
        }

        // Check if the class has a no-argument constructor
        boolean hasNoArgConstructor = getConstructors(jClass).stream()
                .anyMatch(constructor -> constructor.getParamCount() == 0);
        if (!hasNoArgConstructor) {
            return false;
        }

        // Check if the class is not a part of the Spring framework
        if (!className.startsWith("org.springframework")) {
            return false;
        }

        // Additional checks can be added here if needed

        return true;
    }

    public static boolean isPOJOType(Type type){
        return type instanceof ClassType cType && isPOJOClass(cType.getJClass());
    }

    public static boolean isInstantiable(Type type) {
        return (type instanceof ClassType cType && !cType.getJClass().isAbstract())
                || type instanceof ArrayType;
    }
    public static boolean isBasic(Type type){
        //judge if type in enum BaseClasses
        return type instanceof PrimitiveType ||
                (type instanceof ClassType classType && BaseClasses.get().contains(classType.getName()));
    }

    public static boolean isCollectionOrMap(JClass jClass) {
        // Check if the class is a Collection or Map
//        return jClass.getName().equals("java.util.Collection") ||
//                jClass.getName().equals("java.util.Map") ||
//                World.get().getClassHierarchy().getgetSuperClasses().stream().anyMatch(superClass ->
//                        superClass.getName().equals("java.util.Collection") ||
//                                superClass.getName().equals("java.util.Map"));
        return false;
    }

    // return constructor methods of a given class
    public static List<JMethod> getConstructors(JClass jClass) {
        return jClass.getDeclaredMethods().stream()
                .filter(JMethod::isConstructor)
                .collect(Collectors.toList());
    }

    //return all super classes/interfaces of a given class
    public List<JClass> getSuperClasses(JClass jClass) {
        return null;
    }

    public static List<JClass> getImplsOrSubsOf(JClass jClass) {
        if (jClass == null)
            return List.of();
        if (jClass.isInterface() && !jClass.isAbstract()){
            return World.get().getClassHierarchy().getDirectImplementorsOf(jClass).stream().toList();
        }else if (jClass.isAbstract() && !jClass.isInterface()){
            return World.get().getClassHierarchy().getAllSubclassesOf(jClass).stream().toList();
        }else if (jClass.isAbstract() && jClass.isInterface()){
            return World.get().getClassHierarchy().getAllSubclassesOf(jClass).stream().toList();
        }
         return List.of();
    }
    public static JMethod buildJMethod(Method method){
        String name = method.getName();
        JClass jClass = World.get().getClassHierarchy().getClass(method.getDeclaringClass().getTypeName());
        Set<JMethod> methodsWithSameName = jClass.getDeclaredMethods().stream().filter(jMethod -> jMethod.getName().equals(name)).collect(Collectors.toSet());
        if (!methodsWithSameName.isEmpty()){
            List<Type> paramTypes = Arrays.stream(method.getParameterTypes()).map(java.lang.reflect.Type::getTypeName).map(World.get().getTypeSystem()::getType).collect(Collectors.toList());
            JMethod converted = methodsWithSameName.stream().filter(jMethod -> jMethod.getParamTypes().equals(paramTypes)).findFirst().orElse(null);
            return converted;
        }
        return null;
    }
    public static JClass getClassByType(Type type) {
        return World.get().getClassHierarchy().getClass(type.getName());
    }
    public static Set<JMethod> getAllMethods(){
        return World.get().getClassHierarchy().allClasses().flatMap(jClass -> jClass.getDeclaredMethods().stream()).collect(Collectors.toSet());
    }
    public static List<JMethod> getAllAppMethods(String packageName){
        return World.get().getClassHierarchy().allClasses()
                .filter(jClass -> jClass.getName().startsWith(packageName))
                .flatMap(jClass -> jClass.getDeclaredMethods().stream())
                .collect(Collectors.toList());
    }


}
