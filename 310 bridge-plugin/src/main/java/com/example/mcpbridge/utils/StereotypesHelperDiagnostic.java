package com.example.mcpbridge.utils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Diagnostic utility to list all available methods in StereotypesHelper
 * that contain "set", "property", "value", or "tag" in their name or signature.
 *
 * Run this to identify the correct API method for setting stereotype properties.
 */
public class StereotypesHelperDiagnostic {

    private static final String STEREOTYPES_HELPER = "com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper";

    public static void main(String[] args) {
        try {
            Class<?> shClass = Class.forName(STEREOTYPES_HELPER);
            Method[] allMethods = shClass.getDeclaredMethods();

            System.out.println("=".repeat(80));
            System.out.println("StereotypesHelper Methods Related to Property/Value Setting");
            System.out.println("=".repeat(80));

            List<Method> relevantMethods = new ArrayList<>();
            for (Method m : allMethods) {
                String name = m.getName().toLowerCase();
                if (name.contains("set") || name.contains("property") ||
                    name.contains("value") || name.contains("tag")) {
                    relevantMethods.add(m);
                }
            }

            if (relevantMethods.isEmpty()) {
                System.out.println("No methods found containing 'set', 'property', 'value', or 'tag'");
                System.out.println("\nAll public methods in StereotypesHelper:");
                for (Method m : allMethods) {
                    if (m.getParameterCount() <= 5) { // Filter to reasonable param counts
                        System.out.println(formatMethod(m));
                    }
                }
            } else {
                for (Method m : relevantMethods) {
                    System.out.println(formatMethod(m));
                }
            }

            System.out.println("\n" + "=".repeat(80));
            System.out.println("Total methods found: " + relevantMethods.size());
            System.out.println("=".repeat(80));

        } catch (ClassNotFoundException e) {
            System.err.println("ERROR: StereotypesHelper class not found!");
            System.err.println("Verify that MagicDraw libraries are on the classpath.");
            e.printStackTrace();
        }
    }

    private static String formatMethod(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getName()).append("(");

        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            String paramType = params[i].getSimpleName();
            if (params[i].isArray()) {
                paramType = paramType.replace("[]", "") + "[]";
            }
            sb.append(paramType);
        }

        sb.append(") -> ").append(m.getReturnType().getSimpleName());
        return sb.toString();
    }
}
