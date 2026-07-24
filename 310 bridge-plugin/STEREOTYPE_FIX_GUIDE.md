# Stereotype Property Setting - Diagnostic & Fix Guide

## Problem
When setting Requirement stereotype properties (Id, Text), values are being written to the **documentation** field instead of being set as **Tagged Values** (stereotype properties).

## Root Cause
The current implementation uses `setStereotypePropertyValue()` method, which either:
1. Does not exist in MagicDraw 2026x StereotypesHelper
2. Has a different signature than expected
3. Behaves differently than intended

## Diagnostic Steps

### Step 1: Run the Diagnostic Tool
On your Windows machine with Maven installed, run:

```batch
cd "C:\path\to\bridge-plugin"
diagnose.bat
```

This will list all available methods in StereotypesHelper that contain "set", "property", "value", or "tag".

### Step 2: Examine the Output
Look for methods with names like:
- `setStereotypePropertyValue` (may have different parameter signature)
- `setTaggedValue`
- `setValue`
- Other alternatives

Note the exact method signature, including parameter types.

### Step 3: Compare with Current Implementation
The current code in `StereotypePropertiesHandler.java` line 194-197 tries:

```java
Method setVal = shClass.getMethod(
    "setStereotypePropertyValue",
    Project.class, Element.class, stClass, String.class, Object.class);
setVal.invoke(null, project, el, stereotype, propertyName, value);
```

If this method is not listed in the diagnostic output, then this is the problem.

## Manual Fix (If Needed)

If the diagnostic shows a different method signature, update `StereotypePropertiesHandler.java` setProps() method with the correct method call.

### Example: If the correct method is different
```java
// If it's (Element, Stereotype, String, Object)
Method setVal = shClass.getMethod(
    "setStereotypePropertyValue",
    Element.class, stClass, String.class, Object.class);
setVal.invoke(null, el, stereotype, propertyName, value);
```

### Example: If it's a completely different method name
```java
// If it's setTaggedValueValue or similar
Method setVal = shClass.getMethod(
    "setTaggedValueValue",  // different name
    Element.class, stClass, String.class, Object.class);
setVal.invoke(null, el, stereotype, propertyName, value);
```

## Important Notes

1. **Documentation vs. Tagged Values**
   - Documentation: Comments/description field on the element
   - Tagged Values: Stereotype property values (what we want)

2. **Stereotype Application**
   - Before setting property values, the stereotype must be applied to the element
   - Current code assumes the stereotype is already applied
   - If not applied, values may not be set correctly

3. **Session Management**
   - The code creates a session to allow modifications
   - Session must be properly closed or cancelled
   - Current code handles this correctly

## Next Steps

1. Run `diagnose.bat` to identify the correct method
2. If the method found matches the current implementation, the issue may be:
   - Stereotype not applied to element first
   - Different API behavior in 2026x
   - Need to set values directly on tagged value instances

3. If the method is different, update the code and rebuild:
   ```batch
   build.ps1
   ```

4. Test with the MCP API to verify values are set as Tagged Values, not documentation

## Testing the Fix

Use the catia_magic_set_stereotype_property API with:
```json
{
  "project_id": "active",
  "element_id": "_e42",
  "stereotype": "Requirement",
  "property_name": "Id",
  "value": "REQ-001"
}
```

Then check in MagicDraw:
- The "Id" should appear in the Requirement stereotype's tagged values
- It should NOT appear in the element's documentation/comments field
