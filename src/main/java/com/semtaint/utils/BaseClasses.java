package com.semtaint.utils;

import pascal.taie.util.collection.Sets;

import java.util.Set;

public enum BaseClasses {
    OBJECT("java.lang.Object"),
    STRING("java.lang.String"),
    INTEGER("java.lang.Integer"),
    LONG("java.lang.Long"),
    SHORT("java.lang.Short"),
    BYTE("java.lang.Byte"),
    CHAR("java.lang.Character"),
    BOOLEAN("java.lang.Boolean"),
    FLOAT("java.lang.Float"),
    DOUBLE("java.lang.Double"),
//    VOID("java.lang.Void"),
//    CLASS("java.lang.Class"),
//    ENUM("java.lang.Enum"),
//    THROWABLE("java.lang.Throwable"),
//    EXCEPTION("java.lang.Exception"),
//    ERROR("java.lang.Error"),
//    RUNTIME_EXCEPTION("java.lang.RuntimeException"),
//    ILLEGAL_ARGUMENT_EXCEPTION("java.lang.IllegalArgumentException"),
//    ILLEGAL_STATE_EXCEPTION("java.lang.IllegalStateException"),
//    ARRAY_LIST("java.util.ArrayList"),
//    HASH_MAP("java.util.HashMap"),
//    HASH_SET("java.util.HashSet"),
//    LINKED_HASH_SET("java.util.LinkedHashSet"),
//    TREE_SET("java.util.TreeSet"),
//    LINKED_LIST("java.util.LinkedList"),
//    VECTOR("java.util.Vector"),
//    STACK("java.util.Stack"),
//    PRIORITY_QUEUE("java.util.PriorityQueue"),
//    ARRAY_DEQUE("java.util.ArrayDeque"),
//    HASH_TABLE("java.util.Hashtable"),
//    PROPERTIES("java.util.Properties"),
//    DATE("java.util.Date"),
//    CALENDAR("java.util.Calendar"),
//    SIMPLE_DATE_FORMAT("java.text.SimpleDateFormat"),
//    PATTERN("java.util.regex.Pattern"),
//    MATCHER("java.util.regex.Matcher"),
//    SCANNER("java.util.Scanner"),
//    INPUT_STREAM("java.io.InputStream"),
//    OUTPUT_STREAM("java.io.OutputStream"),
//    PRINT_STREAM("java.io.PrintStream"),
//    PRINT_WRITER("java.io.PrintWriter"),
    FILE("java.io.File");
//    FILE_READER("java.io.FileReader"),
//    FILE_WRITER("java.io.FileWriter"),
//    INPUT_STREAM_READER("java.io.InputStreamReader"),
//    OUTPUT_STREAM_WRITER("java.io.OutputStreamWriter"),
//    BUFFERED_READER("java.io.BufferedReader"),
//    BUFFERED_WRITER("java.io.BufferedWriter"),
//    DATA_INPUT_STREAM("java.io.DataInputStream"),
//    DATA_OUTPUT_STREAM("java.io.DataOutputStream"),
//    OBJECT_INPUT_STREAM("java.io.ObjectInputStream"),
//    OBJECT_OUTPUT_STREAM("java.io.ObjectOutputStream"),
//    RANDOM_ACCESS_FILE("java.io.RandomAccess");
private final String value;
    BaseClasses(String value) {
        this.value = value;
    }
    // return all values as set
    public static Set<String> get() {
        Set<String> values = Sets.newSet();
        for (BaseClasses baseClass : BaseClasses.values()) {
            values.add(baseClass.value);
        }
        return values;
    }

    public static void main(String[] args) {
        System.out.println(get());
    }
}
