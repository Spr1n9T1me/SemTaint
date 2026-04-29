package com.semtaint.frame.persistence;

import pascal.taie.language.classes.JMethod;

import java.util.List;
import java.util.Map;

/**
 * Model for persistence layer methods with SQL operation annotations
 * Represents MyBatis and Spring Data persistent operations
 */
public record PersistenceMethodModel(
    JMethod method,
    SqlOperationType operationType,
    String sqlStatement,
    List<String> paramNames,
    Map<String, String> resultMappings
) {
    /**
     * Types of SQL operations
     */
    public enum SqlOperationType {
        SELECT,      // @Select - Data source
        INSERT,      // @Insert - Data sink
        UPDATE,      // @Update - Data sink
        DELETE,      // @Delete - Data sink
        QUERY,       // Spring Data Query methods - Data source
        SAVE,        // Spring Data save - Data sink
        DELETE_BY_ID // Spring Data delete - Data sink
    }

    /**
     * Check if this operation is a data source (reads from database)
     */
    public boolean isSource() {
        return operationType == SqlOperationType.SELECT ||
               operationType == SqlOperationType.QUERY;
    }

    /**
     * Check if this operation is a data sink (writes to database)
     */
    public boolean isSink() {
        return operationType == SqlOperationType.INSERT ||
               operationType == SqlOperationType.UPDATE ||
               operationType == SqlOperationType.DELETE ||
               operationType == SqlOperationType.SAVE ||
               operationType == SqlOperationType.DELETE_BY_ID;
    }
}
