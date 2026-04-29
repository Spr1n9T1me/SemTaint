package com.semtaint;

import pascal.taie.Main;

/**
 * @program: semtaint-newfront
 * @description:
 * @author: springtime
 **/
public class TestContainerModel {
    static String[] test_args = {
            "-java","8",
//            "-pp",
//            "-m","array.MultiDimension",
            "-m","collection.Simple",
            "-scope","APP",
            "-ap",
//            "--ssa",
            "-acp","D:\\Program-Analysis\\Static\\tai-e-tir-test\\target\\classes",
            "--output-dir","D:\\Program-Analysis\\Static\\tai-e-tir-test\\output",
            "-a","ir-dumper",
//            "-a","may-alias-pair",
            "-a","semtaint=cs:ci;propagate-types:[reference,null,int,long];only-app:false;dump-ci:false;merge-string-objects:true;distinguish-string-constants:app;taint-config:D:\\Program-Analysis\\Static\\tai-e-tir-test\\src\\main\\resources\\taint-config.yml;",
//            "-a","side-effect=only-app:false",
//            "-a","process-result=analyses:[side-effect];only-app:true;action-file:side.txt"
    };
    public static void main(String[] args) {
        Main.main(test_args);
    }
}
