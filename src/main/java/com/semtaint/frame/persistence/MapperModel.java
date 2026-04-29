package com.semtaint.frame.persistence;

import pascal.taie.language.classes.JClass;
import pascal.taie.util.collection.Maps;

import java.util.Map;

/**
 * Model for MyBatis Mapper or Spring Data Repository interface
 * Contains all persistence operations defined in the interface
 */
public class MapperModel {
    /**
     * The Mapper/Repository interface class
     */
    private JClass mapperInterface;

    /**
     * The synthetic implementation class created for this interface
     */
    private JClass syntheticImpl;

    /**
     * All persistence methods with their models
     */
    private Map<String, PersistenceMethodModel> persistenceMethods = Maps.newMap();

    /**
     * Result type mappings for complex return types
     * Key: method signature, Value: result type information
     */
    private Map<String, ResultTypeInfo> resultTypeMappings = Maps.newMap();

    /**
     * Framework type: MyBatis or Spring Data
     */
    private FrameworkType frameworkType;

    public enum FrameworkType {
        MYBATIS,
        SPRING_DATA,
        UNKNOWN
    }

    /**
     * Information about result type mapping
     */
    public static class ResultTypeInfo {
        /**
         * The main result type
         */
        private String resultType;

        /**
         * Field-level mappings: database column -> Java field
         */
        private Map<String, String> fieldMappings = Maps.newMap();

        /**
         * Whether the result is a collection
         */
        private boolean isCollection;

        /**
         * Element type if result is a collection
         */
        private String elementType;

        public String getResultType() {
            return resultType;
        }

        public void setResultType(String resultType) {
            this.resultType = resultType;
        }

        public Map<String, String> getFieldMappings() {
            return fieldMappings;
        }

        public void setFieldMappings(Map<String, String> fieldMappings) {
            this.fieldMappings = fieldMappings;
        }

        public boolean isCollection() {
            return isCollection;
        }

        public void setCollection(boolean collection) {
            isCollection = collection;
        }

        public String getElementType() {
            return elementType;
        }

        public void setElementType(String elementType) {
            this.elementType = elementType;
        }
    }

    /**
     * Add a persistence method to this mapper
     */
    public void addPersistenceMethod(String signature, PersistenceMethodModel model) {
        persistenceMethods.put(signature, model);
    }

    /**
     * Add result type mapping information
     */
    public void addResultTypeInfo(String signature, ResultTypeInfo info) {
        resultTypeMappings.put(signature, info);
    }

    // Getters and Setters
    public JClass getMapperInterface() {
        return mapperInterface;
    }

    public void setMapperInterface(JClass mapperInterface) {
        this.mapperInterface = mapperInterface;
    }

    public JClass getSyntheticImpl() {
        return syntheticImpl;
    }

    public void setSyntheticImpl(JClass syntheticImpl) {
        this.syntheticImpl = syntheticImpl;
    }

    public Map<String, PersistenceMethodModel> getPersistenceMethods() {
        return persistenceMethods;
    }

    public void setPersistenceMethods(Map<String, PersistenceMethodModel> persistenceMethods) {
        this.persistenceMethods = persistenceMethods;
    }

    public Map<String, ResultTypeInfo> getResultTypeMappings() {
        return resultTypeMappings;
    }

    public void setResultTypeMappings(Map<String, ResultTypeInfo> resultTypeMappings) {
        this.resultTypeMappings = resultTypeMappings;
    }

    public FrameworkType getFrameworkType() {
        return frameworkType;
    }

    public void setFrameworkType(FrameworkType frameworkType) {
        this.frameworkType = frameworkType;
    }
}
