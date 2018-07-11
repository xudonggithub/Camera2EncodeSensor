package com.example.android.utils;

/**
 * Created by xdchen on 2015/12/28.
 */
public class MathUtils {
    private static final double EPSILON = 0.0001;
    public static boolean floatEquals(float x, float y)
    {
        return (x >= y - EPSILON) && (x <= y + EPSILON);
    }
    public static boolean doubleEquals(double x, double y)
    {
        return (x >= y - EPSILON) && (x <= y + EPSILON);
    }
}
