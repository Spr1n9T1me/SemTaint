package com.semtaint.container.ap;

import pascal.taie.ir.exp.Literal;

/**
 * @program: semtaint-newfront
 * @description:
 * @author: springtime
 **/
public class LiteralPattern extends AccessPattern{
    public LiteralPattern(Literal literal) {
        super(literal);
    }

}
