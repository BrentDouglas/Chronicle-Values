/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.chronicle.values;

import net.openhft.chronicle.bytes.Byteable;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesMarshallable;
import net.openhft.chronicle.bytes.BytesStore;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.values.constraints.Group;
import net.openhft.compiler.CompilerUtils;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import static net.openhft.chronicle.values.ValueModelImpl.heapSize;
import static net.openhft.chronicle.values.ValueModelImpl.smallEnum;

public class ValueGenerator {
    private static final Comparator<Class> COMPARATOR =
            (o1, o2) -> o1.getName().compareTo(o2.getName());
    private static final Comparator<Map.Entry<String, FieldModel>> COMPARE_BY_GROUP_THEN_HEAP_SIZE =
            (o1, o2) -> {
        // descending
        FieldModel model1 = o1.getValue();
        FieldModel model2 = o2.getValue();

        Group group1 = model1.group();
        Group group2 = model2.group();

        int group = Integer.compare(
                group1 == null ? Integer.MIN_VALUE : model1.group().value(),
                group2 == null ? Integer.MIN_VALUE : model2.group().value());

        if (group != 0)
            return group;

        int cmp = -Long.compare(model1.heapSize(), model2.heapSize());
        if (cmp != 0)
            return cmp;
        Class firstPrimitiveFieldType1 = null;
        if (!model1.type().isPrimitive() &&
                !CharSequence.class.isAssignableFrom(model1.type())) {
            firstPrimitiveFieldType1 = firstPrimitiveFieldType(model1.type());
        }
        Class firstPrimitiveFieldType2 = null;
        if (!model2.type().isPrimitive() &&
                !CharSequence.class.isAssignableFrom(model2.type())) {
            firstPrimitiveFieldType2 = firstPrimitiveFieldType(model2.type());
        }
        if (firstPrimitiveFieldType1 != null && firstPrimitiveFieldType2 == null)
            return -1;
        if (firstPrimitiveFieldType1 == null && firstPrimitiveFieldType2 != null)
            return 1;
        if (firstPrimitiveFieldType1 != null && firstPrimitiveFieldType2 != null) {
            return -Long.compare(heapSize(firstPrimitiveFieldType1),
                    heapSize(firstPrimitiveFieldType2));
        }
        return o1.getKey().compareTo(o2.getKey());
    };
    private final Map<Class, Class> heapClassMap = new ConcurrentHashMap<Class, Class>();
    private final Map<Class, Class> nativeClassMap = new ConcurrentHashMap<Class, Class>();
    private boolean dumpCode = Boolean.getBoolean("dvg.dumpCode");

    public static Class firstPrimitiveFieldType(Class valueClass) {
        if (valueClass.getClassLoader() == null)
            return null;
        try {
            ValueModel valueModel;
            if (valueClass.isInterface()) {
                valueModel = ValueModels.acquireModel(valueClass);

            } else {
                String valueClassName = valueClass.getName();
                String $$Native = "$$Native";
                if (valueClassName.endsWith($$Native)) {
                    valueClassName = valueClassName.substring(0,
                            valueClassName.length() - $$Native.length());
                    valueModel = ValueModels.acquireModel(Class.forName(valueClassName));

                } else {
                    return null;
                }
            }
            Map.Entry<String, FieldModel>[] fields =
                    ValueGenerator.heapSizeOrderedFieldsGrouped(valueModel);
            if (fields.length == 0)
                return null;
            Class firstFieldType = fields[0].getValue().type();
            if (firstFieldType.isPrimitive())
                return firstFieldType;
            return firstPrimitiveFieldType(firstFieldType);
        } catch (Exception e) {
            return null;
        }
    }

    public static String bytesType(Class type) {
        if (type.isPrimitive())
            return Character.toUpperCase(type.getName().charAt(0)) + type.getName().substring(1);
        if (CharSequence.class.isAssignableFrom(type))
            return "UTFΔ";
        return "Object";
    }

    static String generateHeapObject(ValueModel<?> dvmodel) {
        SortedSet<Class> imported = newImported();
        imported.add(BytesMarshallable.class);
        imported.add(Bytes.class);
        imported.add(IOException.class);
        imported.add(Copyable.class);
        imported.add(dvmodel.type());

        StringBuilder fieldDeclarations = new StringBuilder();
        Set<Class> enumUniverses = new HashSet<>();
        StringBuilder getterSetters = new StringBuilder();
        StringBuilder writeMarshal = new StringBuilder();
        StringBuilder readMarshal = new StringBuilder();
        StringBuilder copy = new StringBuilder();
        Map.Entry<String, FieldModel>[] entries = heapSizeOrderedFieldsGrouped(dvmodel);
        for (Map.Entry<String, ? extends FieldModel> entry : entries) {
            String name = entry.getKey();
            FieldModel model = entry.getValue();
            Class type = model.type();
            if (shouldImport(type))
                imported.add(type);
            if (Enum.class.isAssignableFrom(type) && !enumUniverses.contains(type)) {
                enumUniverses.add(type);
                fieldDeclarations.append("    private static final " +
                        type.getCanonicalName() + "[] " + universe(type) +
                        " = Enums.getUniverse(" + type.getCanonicalName() + ".class);\n");
            }
            heapFieldDeclarations(fieldDeclarations, type, name, model);

            Method setter = getSetter(model);
            Method getter = getGetter(model);
            Method getUsing = getUsing(model);

            boolean bothVolatileAndPlain = false;

            final Method orderedSetter = getOrderedSetter(model);
            final Method volatileGetter = getVolatileGetter(model);

            if (getter != null && volatileGetter != null) {
                bothVolatileAndPlain = true;
            }
            if (setter == null && orderedSetter != null) {
                setter = orderedSetter;
            }
            if (getter == null && volatileGetter != null) {
                getter = volatileGetter;
            }

            if (setter == null) {
                if (getter != null)
                    copy.append("        ((Copyable) ").append(getter.getName()).append("()).copyFrom(from.").append(getter.getName()).append("());\n");

            } else {
                methodCopy(copy, getter, setter, model);
                methodHeapSet(getterSetters, setter, name, type, model);
            }
            if (getter != null)
                methodHeapGet(getterSetters, getter, name, type, model);

            if (getUsing != null && type == String.class && !model.isArray()) {
                methodHeapGetUsingWithStringBuilder(getterSetters, getUsing, name, type, model);

                // we have to add in the getter method as its required for the equals() and hashCode()
                if (getter == null && volatileGetter == null) {
                    String getterName = getterName(getUsing);
                    methodHeapGet(getterSetters, name, type, getterName);
                }
            }

            //In the case where there are both volatile and plain gets and sets they need to be written here
            //If there is just a volatile get and set it would have been written above.
            if (bothVolatileAndPlain) {
                methodHeapGet(getterSetters, volatileGetter, name, type, model);
                methodHeapSet(getterSetters, orderedSetter, name, type, model);
            }

            Method adder = model.adder();
            if (adder != null) {
                getterSetters.append("    public ").append(normalize(type)).append(' ').append(adder.getName())
                        .append("(").append(adder.getParameterTypes()[0].getName()).append(" $) {\n")
                        .append("        return _").append(name).append(" += $;\n")
                        .append("    }");
            }
            Method sizeOf = model.sizeOf();
            if (sizeOf != null) {
                getterSetters.append("    public int ").append(sizeOf.getName())
                        .append("() {\n")
                        .append("        return ").append(model.indexSize().value()).append(";\n")
                        .append("    }\n\n");
            }
            Method atomicAdder = model.atomicAdder();
            if (atomicAdder != null) {
                getterSetters.append("    public synchronized ").append(normalize(type)).append(' ').append(atomicAdder.getName())
                        .append("(").append(atomicAdder.getParameterTypes()[0].getName()).append(" $) {\n")
                        .append("        return _").append(name).append(" += $;\n")
                        .append("    }\n\n");
            }
            Method cas = model.cas();
            if (cas != null) {
                getterSetters.append("    public synchronized boolean ").append(cas.getName()).append("(")
                        .append(normalize(type)).append(" _1, ")
                        .append(normalize(type)).append(" _2) {\n")
                        .append("        if (_").append(name).append(" == _1) {\n")
                        .append("            _").append(name).append(" = _2;\n")
                        .append("            return true;\n")
                        .append("        }\n")
                        .append("        return false;\n")
                        .append("    }\n");
            }
            methodWriteMarshall(writeMarshal, getter, setter, type, model);
            methodHeapReadMarshall(readMarshal, name, type, model);
        }
        StringBuilder sb = new StringBuilder();
        appendPackage(dvmodel, sb);
        for (Class aClass : imported) {
            sb.append("import ").append(normalize(aClass)).append(";\n");
            sb.append("import static ").append(LongHashCodes.class.getName()).append(".*;");
        }
        String className = simpleName(dvmodel.type());
        sb.append("\npublic class ").append(className)
                .append("$$Heap implements ").append(dvmodel.type().getSimpleName())
                .append(", BytesMarshallable, Copyable<").append(normalize(dvmodel.type())).append(">  {\n");
        sb.append(fieldDeclarations).append('\n');
        sb.append(getterSetters);
        sb.append("    @SuppressWarnings(\"unchecked\")\n" +
                "    public void copyFrom(").append(normalize(dvmodel.type())).append(" from) {\n");
        sb.append(copy);
        sb.append("    }\n\n");
        sb.append("    public void writeMarshallable(Bytes out) {\n");
        sb.append(writeMarshal);
        sb.append("    }\n");
        sb.append("    public void readMarshallable(Bytes in) {\n");
        sb.append(readMarshal);
        sb.append("    }\n");
        if (Byteable.class.isAssignableFrom(dvmodel.type())) {
            sb.append("    public void bytesStore(BytesStore bytes, long offset, long length) {\n");
            sb.append("       throw new UnsupportedOperationException();\n");
            sb.append("    }\n");
            sb.append("    public BytesStore bytesStore() {\n");
            sb.append("       return null;\n");
            sb.append("    }\n");
            sb.append("    public long offset() {\n");
            sb.append("       return 0L;\n");
            sb.append("    }\n");
            sb.append("    public long maxSize() {\n");
            sb.append("       throw new UnsupportedOperationException();\n");
            sb.append("    }\n");
        }
        generateObjectMethods(sb, dvmodel, entries, false);
        sb.append("}\n");
//        System.out.println(sb);
        return sb.toString();
    }

    public static String simpleName(Class<?> type) {
        String name = type.getName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    public static CharSequence normalize(Class aClass) {
        return aClass.getName().replace('$', '.');
    }

    private static void generateObjectMethods(StringBuilder sb, ValueModel<?> dvmodel,
                                              Map.Entry<String, FieldModel>[] entries,
                                              boolean offHeap) {
        int count = 0;
        StringBuilder hashCode = new StringBuilder();
        StringBuilder equals = new StringBuilder();
        StringBuilder equalsGetUsing = new StringBuilder();
        StringBuilder toStringGetUsing = new StringBuilder();
        StringBuilder getUsingEquals = new StringBuilder();
        StringBuilder toString = new StringBuilder();
        for (Map.Entry<String, FieldModel> entry : entries) {
            String name = entry.getKey();
            FieldModel model = entry.getValue();
            Method getter = getGetter(model);
            Method getUsing = getUsing(model);

            if (getter == null) getter = getVolatileGetter(model);

            if (getter != null || getUsing != null) {
                String getterName = (getter == null) ? getterName(getUsing) : getter.getName();
                methodLongHashCode(hashCode, getterName, model, count);

                if (getter != null) {
                    methodEquals(equals, getterName, model, simpleName(dvmodel.type()));
                    methodToString(toString, getterName, name, model);

                } else {
                    methodEqualsGetUsing(getUsingEquals, getUsing.getName());
                    methodToStringGetUsing(toStringGetUsing, getUsing.getName(), name, model);
                }
                count++;
            }

            Bytes b;

            if (model.isArray()) {
                String nameWithUpper = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                if (model.isVolatile()) nameWithUpper = "Volatile" + nameWithUpper;
                sb.append("\n    public long longHashCode_" + name + "() {\n" +
                        "        long hc = 0;\n" +
                        "        for (int i = 0; i < " + model.indexSize().value() + "; i++) {\n" +
                        "            hc += calcLongHashCode(get" + nameWithUpper + "At(i));\n" +
                        "        }\n" +
                        "        return hc;\n" +
                        "    }\n\n");
            }
        }
        sb.append("    public int hashCode() {\n" +
                "        long lhc = longHashCode();\n" +
                "        return (int) ((lhc >>> 32) ^ lhc);\n" +
                "    }\n" +
                "\n" +
                "    public long longHashCode() {\n" +
                "        return ");
        for (int i = 1; i < count; i++)
            sb.append('(');

        sb.append(hashCode);

        CharSequence simpleName = simpleName(dvmodel.type()).replace('$', '.');
        sb.append(";\n")
                .append("    }\n")
                .append("\n");
        sb.append("    public boolean equals(Object o) {\n")
                .append("        if (this == o) return true;\n")
                .append("        if (!(o instanceof ").append(simpleName).append(")) return false;\n")
                .append("        ").append(simpleName).append(" that = (").append(simpleName).append(") o;\n")
                .append("\n")
                .append(equals)
                .append(equalsGetUsing)
                .append("        return true;\n")
                .append("    }\n")
                .append("\n");
        sb.append("    public String toString() {\n")
                .append(offHeap ? "        if (_bytes == null) return \"bytes is null\";\n" : "")
                .append("        StringBuilder sb = new StringBuilder();\n")
                .append("        sb.append(\"").append(simpleName).append("{ \");\n")
                .append(toString)
                .append(toStringGetUsing)
                .append("        sb.append(\" }\");\n")
                .append("        return sb.toString();\n")
                .append("    }\n");
    }

    private static Method getUsing(FieldModel model) {
        Method getUsing = model.getUsing();
        return getUsing;
    }

    public static Method getGetter(FieldModel model) {
        Method getter = model.getter();
        if (getter == null) getter = model.indexedGetter();
        return getter;
    }

    public static Method getVolatileGetter(FieldModel model) {
        Method getter = model.volatileGetter();
        if (getter == null) getter = model.volatileIndexedGetter();
        return getter;
    }

    public static Method getSetter(FieldModel model) {
        Method setter = model.setter();
        if (setter == null) setter = model.indexedSetter();

        return setter;
    }

    public static Method getOrderedSetter(FieldModel model) {
        Method setter = model.orderedSetter();
        if (setter == null) setter = model.orderedIndexedSetter();

        return setter;
    }

    private static void methodCopy(
            StringBuilder copy, Method getter, Method setter, FieldModel model) {
        if (!model.isArray()) {
            if (model.setter() != null && getter != null) {
                copy.append("        ").append(setter.getName());
                copy.append("(from.").append(getter.getName()).append("());\n");
            }
        } else {
            copy.append("        for (int i = 0; i < ").append(model.indexSize().value())
                    .append("; i++){");
            copy.append("\n            ").append(setter.getName()).append("(i, from.")
                    .append(getter.getName()).append("(i));\n");
            copy.append("        }\n");
        }
    }

    private static void methodWriteMarshall(StringBuilder writeMarshal, Method getter,
                                            Method setter, Class type, FieldModel model) {
        if (!model.isArray()) {
            if (getter != null && setter != null) {
                writeMarshal.append("        {\n");
//                saveCharSequencePosition(writeMarshal, type, "out");
                writeMarshal.append("        out.write").append(bytesType(type)).append("(")
                        .append(getter.getName()).append("());\n");
//                zeroOutRemainingCharSequenceBytesAndUpdatePosition(
//                        writeMarshal, model, type, "out");
                writeMarshal.append("        }\n");
            }
            // otherwise skip.
        } else {
            writeMarshal.append("        for (int i = 0; i < ")
                    .append(model.indexSize().value()).append("; i++){\n");
//            saveCharSequencePosition(writeMarshal, type, "out");
            writeMarshal.append("            out.write").append(bytesType(type)).append("(")
                    .append(getter.getName()).append("(i));\n");
//            zeroOutRemainingCharSequenceBytesAndUpdatePosition(
//                    writeMarshal, model, type, "out");
            writeMarshal.append("        }\n");
        }
    }

    private static void saveCharSequencePosition(StringBuilder write, Class type, String bytes) {
        if (CharSequence.class.isAssignableFrom(type))
            write.append("            long pos = " + bytes + ".writePosition();\n");
    }

    private static void zeroOutRemainingCharSequenceBytesAndUpdatePosition(
            StringBuilder write, FieldModel model, Class type, String bytes) {
        if (CharSequence.class.isAssignableFrom(type)) {
            write.append("            long newPos = pos + ").append(fieldSize(model))
                    .append(";\n");
            write.append("            " + bytes + ".zeroOut(" + bytes +  ".position(), newPos);\n");
            write.append("            " + bytes + ".writePosition(newPos);\n");
        }
    }

    private static void methodHeapReadMarshall(StringBuilder readMarshal, String name, Class type, FieldModel model) {

        if(type == Date.class) {
            readMarshal.append("        _").append(name).append(" = new Date(in.readLong());\n");
        } else if (Enum.class.isAssignableFrom(type)) {
            readMarshal.append("        _").append(name).append(" = ").append(readEnum("in", type, false)).append(";\n");
        } else {
            String str = bytesType(type);
            if (!model.isArray()) {
                readMarshal.append("        _").append(name).append(" = in.read").append(str).append("(");
                if ("Object".equals(str))
                    readMarshal.append(normalize(type)).append(".class");
                readMarshal.append(");\n");

            } else {
                readMarshal.append("        for (int i = 0; i < ").append(model.indexSize().value()).append("; i++){\n");
                readMarshal.append("            _").append(name).append("[i] = in.read").append(str).append("(");
                if ("Object".equals(str))
                    readMarshal.append(normalize(type)).append(".class");
                readMarshal.append(");\n");
                readMarshal.append("        }\n");
            }
        }
    }

    private static void methodLongHashCode(StringBuilder hashCode, String getterName, FieldModel model, int count) {
        if (count > 0)
            hashCode.append(") * 10191 +\n            ");

        if (!model.isArray()) {
            hashCode.append("calcLongHashCode(").append(getterName).append("())");

        } else {
            hashCode.append("longHashCode_").append(model.name()).append("()");
        }
    }

    private static void methodEqualsGetUsing(StringBuilder equals, String getterName) {
        equals.append("        if(").append(getterName).append("new StringBuilder()).toString().equals(that.").append(getterName).append("new StringBuilder().toString())) return false;\n");
    }

    private static void methodEquals(StringBuilder equals, String getterName, FieldModel model, String className) {
        Class type = model.type();
        BiFunction<String, String, String> isEqual =
                type.isPrimitive() || Enum.class.isAssignableFrom(type) ?
                (l, r) -> "(" + l + " != " + r + ")" :
                (l, r) -> "!Objects.equals(" + l + ", " + r + ")";
        if (!model.isArray()) {
            equals.append("        if (").append(
                    isEqual.apply(getterName + "()", "that." + getterName + "()")).append(") return false;\n");

        } else {
            equals.append("        for (int i = 0; i <" + model.indexSize().value() + "; i++) {\n");
            equals.append("            if (").append(
                    isEqual.apply(getterName + "(i)", "that." + getterName + "(i)")).append(") return false;\n");
            equals.append("        }\n");
        }
    }

    private static void methodToStringGetUsing(StringBuilder toString, String getterName, String name, FieldModel model) {
        toString.append("            sb.append(\"").append(name).append("= \").append(").append(getterName).append("(new StringBuilder()));\n");
    }

    private static void methodToString(StringBuilder toString, String getterName, String name, FieldModel model) {
        if (toString.length() > 2)
            toString.append("sb.append(\", \")\n;");
        if (!model.isArray()) {
            toString.append("            sb.append(\"").append(name).append("= \").append(").append(getterName).append("());\n");

        } else {
            toString
                    .append("              sb.append(\"").append(name).append("\").append(\"= [\");")
                    .append("              for (int i = 0; i < ").append(model.indexSize().value()).append("; i++) {\n")
                    .append("                  if (i > 0) sb.append(\", \") ;\n")
                    .append("                  sb.append(").append(getterName).append("(i));\n")
                    .append("              }\n")
                    .append("              sb.append(\"]\");\n");
        }
    }

    private static String nullAwareToString(String var) {
        return "(" + var + " != null ? " + var + ".toString() : null)";
    }

    private static void methodHeapSet(StringBuilder getterSetters, Method setter, String name, Class type, FieldModel model) {
        Class<?> setterType = setter.getParameterTypes()[setter.getParameterTypes().length - 1];
        if (!model.isArray()) {
            getterSetters.append("    public void ").append(setter.getName()).append('(').append(normalize(setterType)).append(" $) {\n");
            if (type == String.class && setterType != String.class)
                getterSetters.append("        _").append(name).append(" = " + nullAwareToString("$") + ";\n");
            else
                getterSetters.append("        _").append(name).append(" = $;\n");

        } else {
            getterSetters.append("    public void ").append(setter.getName()).append("(int i, ").append(normalize(setterType)).append(" $) {\n");
            getterSetters.append(boundsCheck(model.indexSize().value()));
            if (type == String.class && setterType != String.class)
                getterSetters.append("        _").append(name).append("[i] = " + nullAwareToString("$") + ";\n");
            else
                getterSetters.append("        _").append(name).append("[i] = $;\n");
        }
        getterSetters.append("    }\n\n");
    }

    private static void methodHeapGet(StringBuilder getterSetters, Method getter, String name, Class type, FieldModel model) {
        if (!model.isArray()) {
            getterSetters.append("    public ").append(normalize(type)).append(' ').append(getter.getName()).append("() {\n");
            getterSetters.append("        return _").append(name).append(";\n");

        } else {
            getterSetters.append("    public ").append(normalize(type)).append(' ').append(getter.getName()).append("(int i) {\n");
            getterSetters.append(boundsCheck(model.indexSize().value()));
            getterSetters.append("        return _").append(name).append("[i];\n");
        }
        getterSetters.append("    }\n\n");
    }

    private static void methodHeapGet(StringBuilder getterSetters, String name, Class type, String getterName) {
        getterSetters.append("    public ").append(normalize(type)).append(' ').append(getterName).append("() {\n");
        getterSetters.append("        return _").append(name).append(";\n");
        getterSetters.append("    }\n\n");
    }

    private static void methodHeapGetUsingWithStringBuilder(StringBuilder result, Method method, String name, Class type, FieldModel model) {
        final CharSequence returnType = method.getReturnType() == void.class ? "void" : normalize(method
                .getReturnType());

        if (!type.equals(String.class) || method.getParameterTypes().length != 1)
            return;

        if (!StringBuilder.class.equals(method.getParameterTypes()[0]))
            return;

        result.append("    public ").append(returnType).append(' ').append(method
                .getName())
                .append("(StringBuilder builder){\n");

        result.append("        builder.append(_" + name + ");\n");

        if (method.getReturnType() != void.class)
            result.append("        return builder;\n");

        result.append("    }\n\n");
    }

    private static void heapFieldDeclarations(StringBuilder fieldDeclarations, Class type, String name, FieldModel model) {
        String vol = "";
        if (model.isVolatile()) vol = "volatile ";

        if (!model.isArray()) {
            fieldDeclarations.append("    private ").append(vol).append(normalize(type)).append(" _").append(name).append(";\n");

        } else {
            fieldDeclarations.append("    private ").append(vol).append(normalize(type)).append("[] _").append(name)
                    .append(" = new ").append(normalize(type)).append("[").append(model.indexSize().value()).append("];\n");
            if (!type.isPrimitive()) {
                fieldDeclarations.append("    {\n")
                        .append("        for (int i = 0; i < _").append(name).append(".length; i++)\n")
                        .append("            _").append(name).append("[i] = new ").append(type.getName());

                if (type.isInterface()) {
                    fieldDeclarations.append("$$Heap();\n");

                } else {
                    fieldDeclarations.append("();\n");
                }
                fieldDeclarations.append("    }");
            }
        }
    }

    private static String boundsCheck(int check) {
        return "        if(i<0) throw new ArrayIndexOutOfBoundsException(i + \" must be greater than 0\");\n" +
                "        if(i>=" + check + ") throw new ArrayIndexOutOfBoundsException(i + \" must be less than " + check + "\");\n";
    }

    public static void appendImported(SortedSet<Class> imported, StringBuilder sb) {
        for (Class aClass : imported) {
            sb.append("import ").append(aClass.getName().replace('$', '.')).append(";\n");
        }
    }

    public static void appendPackage(ValueModel<?> dvmodel, StringBuilder sb) {
        sb.append("package ").append(getPackage(dvmodel)).append(";\n\n");
    }

    public static String getPackage(ValueModel<?> dvmodel) {
        return dvmodel.type().getPackage().getName();
    }

    public static int fieldSize(FieldModel model) {
        return computeOffset(
                (int) Maths.divideRoundUp((long) model.nativeSize(), 8), model);
    }

    public static TreeSet<Class> newImported() {
        return new TreeSet<Class>(COMPARATOR);
    }

    public static boolean shouldImport(Class type) {
        return !type.isPrimitive() && !type.getPackage().getName().equals("java.lang");
    }

    public static Map.Entry<String, FieldModel>[] heapSizeOrderedFieldsGrouped(ValueModel<?> dvmodel) {
        Map<String, ? extends FieldModel> fieldMap = dvmodel.fieldMap();
        Map.Entry<String, FieldModel>[] entries =
                fieldMap.entrySet().toArray(new Map.Entry[fieldMap.size()]);
        Arrays.sort(entries, COMPARE_BY_GROUP_THEN_HEAP_SIZE);
        return entries;
    }

    /**
     * gets the getter name based on the getUsing
     */
    private static String getterName(Method getUsingMethod) {
        String name = getUsingMethod.getName();
        if (!name.startsWith("getUsing"))
            throw new IllegalArgumentException("expected the getUsingXX method to start with the text 'getUsing'.");
        return "get" + name.substring("getUsing".length());
    }

    private static void methodGetUsingWithStringBuilder(StringBuilder result, Method method, Class type, boolean isVolatile, String name) {
        String read = "read";
        if (isVolatile) read = "readVolatile";

        if (method.getParameterTypes().length != 1)
            return;

        if (!StringBuilder.class.equals(method.getParameterTypes()[0]))
            return;

        if (type != String.class)
            return;

        final CharSequence returnType = method.getReturnType() == void.class ? "void" : normalize(method
                .getReturnType());

        result.append("    public ").append(returnType).append(' ').append(method
                .getName())
                .append("(StringBuilder builder){\n");

        result.append("     _bytes.readPosition(_offset + ").append(name.toUpperCase()).append(");\n");
        result.append("     _bytes.").append(read).append(bytesType(type)).append("(builder);\n");

        if (method.getReturnType() != void.class) {
            result.append("     return builder;\n");
        }
        result.append("    }\n\n");
    }

    public static int computeOffset(int offset, FieldModel model) {
        if (model.indexSize() == null) {
            return offset;

        } else {
            return model.indexSize().value() * offset;
        }
    }

    public static int computeNonScalarOffset(ValueModel dvmodel, Class type) {
        int offset = 0;
        ValueModel dvmodel2 = dvmodel.nestedModel(type);
        Map.Entry<String, FieldModel>[] entries2 = heapSizeOrderedFieldsGrouped(dvmodel2);
        for (Map.Entry<String, ? extends FieldModel> entry2 : entries2) {
            FieldModel model2 = entry2.getValue();
            int add;
            if (dvmodel2.isScalar(model2.type())) {
                add = fieldSize(model2);

            } else {
                add = computeNonScalarOffset(dvmodel2, model2.type());
                if (model2.isArray())
                    add *= model2.indexSize().value();
            }
            offset += add;
        }
        return offset;
    }

    public <T> T heapInstance(Class<T> tClass) {
        try {
            //noinspection ClassNewInstance
            return (T) acquireHeapClass(tClass).newInstance();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public <T> Class acquireHeapClass(Class<T> tClass) {
        Class heapClass = heapClassMap.get(tClass);
        if (heapClass != null)
            return heapClass;
        ClassLoader classLoader = tClass.getClassLoader();
        String className = tClass.getName() + "$$Heap";
        try {
            heapClass = classLoader.loadClass(className);
        } catch (ClassNotFoundException ignored) {
            try {
                String actual = generateHeapObject(tClass);
                if (dumpCode)
                    LoggerFactory.getLogger(ValueGenerator.class).info(actual);
                heapClass = CompilerUtils.CACHED_COMPILER.loadFromJava(classLoader, className, actual);
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
        }
        heapClassMap.put(tClass, heapClass);
        return heapClass;
    }

    String generateHeapObject(Class<?> tClass) {
        ValueModel<?> dvmodel = ValueModels.acquireModel(tClass);
        for (FieldModel fieldModel : dvmodel.fieldMap().values()) {
            if (fieldModel.isArray() && !fieldModel.type().isPrimitive())
                acquireHeapClass(fieldModel.type());
        }
        return generateHeapObject(dvmodel);
    }

    public <T> T nativeInstance(Class<T> tClass) {
        try {
            //noinspection ClassNewInstance
            return (T) acquireNativeClass(tClass).newInstance();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public <T> Class acquireNativeClass(Class<T> tClass) {
        if (!tClass.isInterface())
            return tClass;
        Class nativeClass = nativeClassMap.get(tClass);
        if (nativeClass != null)
            return nativeClass;
        ValueModel<T> dvmodel = ValueModels.acquireModel(tClass);
        for (Class clazz : dvmodel.nestedModels()) {
            // touch them to make sure they are loaded.
            Class clazz2 = acquireNativeClass(clazz);
        }
        String actual = new ValueGenerator().generateNativeObject(dvmodel);
        if (dumpCode)
            LoggerFactory.getLogger(ValueGenerator.class).info(actual);
        ClassLoader classLoader = tClass.getClassLoader();
        String className = tClass.getName() + "$$Native";
        try {
            nativeClass = classLoader.loadClass(className);
        } catch (ClassNotFoundException ignored) {
            try {
                nativeClass = CompilerUtils.CACHED_COMPILER.loadFromJava(classLoader, className, actual);
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
        }
        nativeClassMap.put(tClass, nativeClass);
        return nativeClass;
    }

    public String generateNativeObject(Class<?> tClass) {
        return generateNativeObject(ValueModels.acquireModel(tClass));
    }

    private static String universe(Class<?> enumType) {
        return enumType.getCanonicalName().replace(".", "") + "Universe";
    }

    public String generateNativeObject(ValueModel<?> dvmodel) {
        SortedSet<Class> imported = newImported();
        imported.add(BytesMarshallable.class);
        imported.add(ObjectOutput.class);
        imported.add(ObjectInput.class);
        imported.add(IOException.class);
        imported.add(Copyable.class);
        imported.add(Byteable.class);
        imported.add(BytesStore.class);
        imported.add(Bytes.class);
        imported.add(Enums.class);

        StringBuilder staticFieldDeclarations = new StringBuilder();
        Set<Class> enumUniverses = new HashSet<>();
        StringBuilder fieldDeclarations = new StringBuilder();
        StringBuilder getterSetters = new StringBuilder();
        StringBuilder writeMarshal = new StringBuilder();
        StringBuilder readMarshal = new StringBuilder();
        StringBuilder copy = new StringBuilder();
        StringBuilder nestedBytes = new StringBuilder();

        Map.Entry<String, FieldModel>[] entries = heapSizeOrderedFieldsGrouped(dvmodel);
        int offset = 0;
        for (Map.Entry<String, ? extends FieldModel> entry : entries) {
            String name = entry.getKey();
            FieldModel model = entry.getValue();
            Class type = model.type();
            if (shouldImport(type))
                imported.add(type);
            String NAME = "_offset + " + name.toUpperCase();
            final Method setter = getSetter(model);
            final Method getter = getGetter(model);
            final Method getUsing = getUsing(model);

            final Method orderedSetter = getOrderedSetter(model);
            final Method volatileGetter = getVolatileGetter(model);

            final Method defaultSetter = setter != null ? setter : orderedSetter;
            final Method defaultGetter = getter != null ? getter : volatileGetter;

            if (dvmodel.isScalar(type)) {
                staticFieldDeclarations.append("    private static final int ").append(name.toUpperCase()).append(" = ").append(offset).append(";\n");
                if (Enum.class.isAssignableFrom(type) && !enumUniverses.contains(type)) {
                    enumUniverses.add(type);
                    staticFieldDeclarations.append("    private static final " +
                            type.getCanonicalName() + "[] " + universe(type) +
                            " = Enums.getUniverse(" + type.getCanonicalName() + ".class);\n");
                }
                methodCopy(copy, defaultGetter, defaultSetter, model);
                if (setter != null)
                    methodSet(getterSetters, setter, type, NAME, model, false);
                if (getter != null)
                    methodGet(getterSetters, getter, type, NAME, model, false);
                if (getUsing != null) {
                    methodGetUsingWithStringBuilder(getterSetters, getUsing, type, false, name);

                    // we have to add in the getter method as its required for the equals() and hashCode()
                    if (getter == null && volatileGetter == null) {
                        String getterName = getterName(getUsing);
                        methodGet(getterSetters, type, NAME, false, getterName);
                    }
                }

                if (orderedSetter != null)
                    methodSet(getterSetters, orderedSetter, type, NAME, model, true);
                if (volatileGetter != null)
                    methodGet(getterSetters, volatileGetter, type, NAME, model, true);

                Method adder = model.adder();
                if (adder != null) {
                    getterSetters.append("    public ").append(normalize(type)).append(' ').append(adder.getName())
                            .append("(").append(adder.getParameterTypes()[0].getName()).append(" $) {\n")
                            .append("        return _bytes.addAndGet").append(bytesType(type)).append("NotAtomic(").append(NAME).append(", $);\n")
                            .append("    }");
                }
                Method atomicAdder = model.atomicAdder();
                if (atomicAdder != null) {
                    getterSetters.append("    public ").append(normalize(type)).append(' ').append(atomicAdder.getName())
                            .append("(").append(atomicAdder.getParameterTypes()[0].getName()).append(" $) {\n")
                            .append("        return _bytes.addAndGet").append(bytesType(type)).append("(").append(NAME).append(", $);\n")
                            .append("    }");
                }
                Method sizeOf = model.sizeOf();
                if (sizeOf != null) {
                    getterSetters.append("    public int ").append(sizeOf.getName())
                            .append("() {\n").append("        return ").append(model.indexSize().value()).append(";\n")
                            .append("    }\n\n");
                }
                Method cas = model.cas();
                if (cas != null) {
                    getterSetters.append("    public boolean ").append(cas.getName()).append("(")
                            .append(normalize(type)).append(" _1, ")
                            .append(normalize(type)).append(" _2) {\n")
                            .append("        return _bytes.compareAndSwap").append(bytesType(type)).append('(').append(NAME).append(", _1, _2);\n")
                            .append("    }");
                }
                methodWriteMarshall(writeMarshal, defaultGetter, defaultSetter, type, model);
                methodReadMarshall(readMarshal, defaultGetter, defaultSetter, type, model);

                offset += fieldSize(model);

            } else {
                staticFieldDeclarations.append("    private static final int ").append(name.toUpperCase()).append(" = ").append(offset).append(";\n");
                nonScalarFieldDeclaration(staticFieldDeclarations, type, name, model);
                if (defaultSetter == null) {
                    copy.append("        _").append(name).append(".copyFrom(from.").append(getter.getName()).append("());\n");

                } else {
                    methodCopy(copy, defaultGetter, defaultSetter, model);
                    methodNonScalarSet(getterSetters, defaultSetter, name, type, model);
                }

                int size = computeNonScalarOffset(dvmodel, type);
                methodNonScalarGet(getterSetters, getter, name, type, model);
                methodNonScalarWriteMarshall(writeMarshal, name, model);
                methodNonScalarReadMarshall(readMarshal, name, model);
                methodNonScalarBytes(nestedBytes, name, NAME, size, model);

                offset += computeOffset(size, model);
            }
        }
        fieldDeclarations.append("\n")
                .append("    private BytesStore _bytes;\n")
                .append("    private long _offset;\n");
        StringBuilder sb = new StringBuilder();
        appendPackage(dvmodel, sb);
        appendImported(imported, sb);
        sb.append("import static ").append(LongHashCodes.class.getName()).append(".*;");

        sb.append("\npublic class ").append(simpleName(dvmodel.type()))
                .append("$$Native implements ").append(simpleName(dvmodel.type()).replace('$', '.'))
                .append(", BytesMarshallable, Byteable, Copyable<").append(normalize(dvmodel.type())).append("> {\n");
        sb.append(staticFieldDeclarations).append('\n');
        sb.append(fieldDeclarations).append('\n');
        sb.append(getterSetters);
        sb.append("    @Override\n")
                .append("    public void copyFrom(").append(normalize(dvmodel.type())).append(" from) {\n")
                .append(copy)
                .append("    }\n\n");
        sb.append("    @Override\n")
                .append("    public void writeMarshallable(Bytes out) {\n")
                .append(writeMarshal)
                .append("    }\n");
        sb.append("    @Override\n")
                .append("    public void readMarshallable(Bytes in) {\n")
                .append(readMarshal)
                .append("    }\n");
        sb.append("    @Override\n")
                .append("    public void bytesStore(BytesStore bytesStore, long offset, long length) {\n")
                .append("       if (length != maxSize()) throw new IllegalArgumentException();\n")
                .append("       this._bytes = bytesStore;\n")
                .append("       this._offset = offset;\n")
                .append(nestedBytes)
                .append("    }\n");
        sb.append("    @Override\n")
                .append("    public BytesStore bytesStore() {\n")
                .append("       return _bytes;\n")
                .append("    }\n");
        sb.append("    @Override\n")
                .append("    public long offset() {\n")
                .append("        return _offset;\n")
                .append("    }\n");
        sb.append("    @Override\n")
                .append("    public long maxSize() {\n")
                .append("       return ").append(offset).append(";\n")
                .append("    }\n");

        generateObjectMethods(sb, dvmodel, entries, true);
        sb.append("}\n");
//        System.out.println(sb);
        return sb.toString();
    }

    public boolean isDumpCode() {
        return dumpCode;
    }

    public void setDumpCode(boolean dumpCode) {
        this.dumpCode = dumpCode;
    }

    private void methodSet(StringBuilder getterSetters, Method setter, Class type, String NAME, FieldModel model, boolean isVolatile) {
        Class<?> setterType = setter.getParameterTypes()[setter.getParameterTypes().length - 1];
        String write = "write";
        if (isVolatile) write = "writeOrdered";
        if (model.type() == Date.class) {
            getterSetters.append("\n\n    public void ").append(setter.getName()).append('(').append(normalize(setterType)).append(" $) {\n");
            getterSetters.append("        _bytes.").append(write).append("Long").append("(").append(NAME).append(", ");
        } else if(Enum.class.isAssignableFrom(type)){
            getterSetters.append("\n\n    public void ").append(setter.getName()).append('(').append(normalize(setterType)).append(" $) {\n");
            getterSetters.append("        _bytes.").append(write)
                    .append(smallEnum(type) ? "UnsignedByte" : "UnsignedShort").append("(");
        } else if (!model.isArray()) {
            getterSetters.append("\n\n    public void ").append(setter.getName()).append('(').append(normalize(setterType)).append(" $) {\n");
            getterSetters.append("        _bytes.").append(write).append(bytesType(type)).append("(").append(NAME).append(", ");

        } else {
            getterSetters.append("    public void ").append(setter.getName()).append("(int i, ");
            getterSetters.append(normalize(setterType)).append(" $) {\n");
            getterSetters.append(boundsCheck(model.indexSize().value()));
            getterSetters.append("        _bytes.").append(write).append(bytesType(type)).append("(").append(NAME);
            getterSetters.append(" + i * ").append((model.nativeSize() + 7) >> 3).append(", ");
        }

        if (CharSequence.class.isAssignableFrom(type))
            getterSetters.append(model.size().value()).append(", ");

        if (model.type() == Date.class) {
            getterSetters.append("$.getTime());\n");
        } else if (Enum.class.isAssignableFrom(type)) {
            getterSetters.append("$.ordinal());\n");
        } else {
            getterSetters.append("$);\n");
        }
        getterSetters.append("    }\n\n");
    }
    private void methodGet(StringBuilder getterSetters, Class type, String NAME, boolean isVolatile, String name) {
        String read = "read";
        if (isVolatile) read = "readVolatile";
        getterSetters.append("    public ").append(normalize(type)).append(' ').append(name).append("() {\n");
        getterSetters.append("        return _bytes.").append(read).append(bytesType(type)).append("(").append(NAME).append(");\n");
        getterSetters.append("    }\n\n");
    }

    private void methodGet(StringBuilder getterSetters, Method getter, Class type, String NAME, FieldModel model, boolean isVolatile) {
        String read = "read";
        if (isVolatile) read = "readVolatile";

        Class modelType = model.type();
        if(modelType == Date.class){
            getterSetters.append("    public ").append(normalize(type)).append(' ').append(getter.getName()).append("() {\n");
            getterSetters.append("        return new Date( _bytes.").append(read).append("Long").append("(").append(NAME).append("));\n");
        } else if (Enum.class.isAssignableFrom(modelType)) {
            getterSetters.append("    public ").append(normalize(type)).append(' ').append(getter.getName()).append("() {\n");
            getterSetters.append("        return ").append(readEnum("_bytes", modelType, isVolatile)).append(";\n");
        } else if (!model.isArray()) {
            getterSetters.append("    public ").append(normalize(type)).append(' ').append(getter.getName()).append("() {\n");
            getterSetters.append("        return _bytes.").append(read).append(bytesType(type)).append("(").append(NAME).append(");\n");

        } else {
            getterSetters.append("    public ").append(normalize(type)).append(' ').append(getter.getName()).append("(int i) {\n");
            getterSetters.append(boundsCheck(model.indexSize().value()));
            getterSetters.append("        return _bytes.").append(read).append(bytesType(type)).append("(").append(NAME);
            getterSetters.append(" + i * ").append((model.nativeSize() + 7) >> 3);
            getterSetters.append(");\n");
        }
        getterSetters.append("    }\n\n");
    }

    private static String readEnum(String bytes, Class<?> type, boolean isVolatile) {
        return universe(type) + "[" + bytes + ".read" + (isVolatile ? "Volatile" : "") + "Unsigned" +
                (smallEnum(type) ? "Byte" : "Short") + "()]";
    }

    private void methodNonScalarWriteMarshall(StringBuilder writeMarshal, String name, FieldModel model) {
        if (!model.isArray()) {
            writeMarshal.append("         _").append(name).append(".writeMarshallable(out);\n");

        } else {
            writeMarshal.append("        for (int i = 0; i < ").append(model.indexSize().value()).append("; i++){\n");
            writeMarshal.append("            _").append(name).append("[i].writeMarshallable(out);\n");
            writeMarshal.append("        }\n");
        }
    }

    private void methodReadMarshall(StringBuilder readMarshal, Method getter, Method setter, Class type, FieldModel model) {
        Class modelType = model.type();
        if(modelType == Date.class){
            if (getter != null && setter != null)
                readMarshal.append("        ").append(setter.getName()).append("((Date)in.read").append(bytesType(type)).append("());\n");
        } else if (Enum.class.isAssignableFrom(modelType)) {
            if (getter != null && setter != null)
                readMarshal.append("        ").append(setter.getName()).append("(" + readEnum("in", modelType, false) + ");\n");
        } else if (!model.isArray()) {
            if (getter != null && setter != null)
                readMarshal.append("        ").append(setter.getName()).append("(in.read").append(bytesType(type)).append("());\n");

        } else {
            readMarshal.append("        for (int i = 0; i < ").append(model.indexSize().value()).append("; i++){\n");
            readMarshal.append("            ").append(setter.getName()).append("(i, in.read").append(bytesType(type)).append("());\n");
            readMarshal.append("        }\n");
        }
    }

    private void methodNonScalarReadMarshall(StringBuilder readMarshal, String name, FieldModel model) {
        if (!model.isArray()) {
            readMarshal.append("         _").append(name).append(".readMarshallable(in);\n");

        } else {
            readMarshal.append("        for (int i = 0; i < ").append(model.indexSize().value()).append("; i++){\n");
            readMarshal.append("            _").append(name).append("[i].readMarshallable(in);\n");
            readMarshal.append("        }\n");
        }
    }

    private void nonScalarFieldDeclaration(StringBuilder fieldDeclarations, Class type, String name, FieldModel model) {
        fieldDeclarations.append("    private final ").append(type.getName()).append("$$Native _").append(name);
        if (!model.isArray()) {
            fieldDeclarations.append(" = new ").append(type.getName()).append("$$Native();\n");

        } else {
            fieldDeclarations.append("[] = new ").append(type.getName()).append("$$Native[").append(model.indexSize().value()).append("];\n");
            fieldDeclarations.append("    {\n")
                    .append("        for (int i = 0; i < ").append(model.indexSize().value()).append("; i++)\n")
                    .append("            _").append(name).append("[i] = new ").append(type.getName()).append("$$Native();\n")
                    .append("    }\n");
        }
    }

    private void methodNonScalarSet(StringBuilder getterSetters, Method setter, String name, Class type, FieldModel model) {
        Class<?> setterType = setter.getParameterTypes()[setter.getParameterTypes().length - 1];

        if (!model.isArray()) {
            getterSetters.append("    public void ").append(setter.getName()).append('(').append(normalize(setterType)).append(" $) {\n");
            if (type == String.class && setterType != String.class)
                getterSetters.append("        _").append(name).append(" = " + nullAwareToString("$") + ";\n");
            else
                getterSetters.append("        _").append(name).append(".copyFrom($);\n");

        } else {
            getterSetters.append("    public void ").append(setter.getName()).append("(int i, ").append(normalize(setterType)).append(" $) {\n");
            if (type == String.class && setterType != String.class)
                getterSetters.append("        _").append(name).append("[i] = " + nullAwareToString("$") + ";\n");
            else
                getterSetters.append("        _").append(name).append("[i].copyFrom($);\n");
        }
        getterSetters.append("    }\n\n");
    }

    private void methodNonScalarGet(StringBuilder getterSetters, Method getter, String name, Class type, FieldModel model) {
        if (!model.isArray()) {
            getterSetters.append("    public ").append(normalize(type)).append(' ').append(getter.getName()).append("() {\n");
            getterSetters.append("        return _").append(name).append(";\n");

        } else {
            getterSetters.append("    public ").append(normalize(type)).append(' ').append(getter.getName()).append("(int i) {\n");
            getterSetters.append("        return _").append(name).append("[i];\n");
        }
        getterSetters.append("    }\n\n");
    }

    private void methodNonScalarBytes(StringBuilder nestedBytes, String name, String NAME, int size, FieldModel model) {
        if (!model.isArray()) {
            nestedBytes.append("        ((Byteable) _").append(name).append(").bytes(bytes, ").append(NAME).append(");\n");

        } else {
            nestedBytes.append("       for (int i = 0; i < ").append(model.indexSize().value()).append("; i++){\n");
            nestedBytes.append("           ((Byteable) _").append(name).append("[i]).bytes(bytes, ").append(NAME);
            nestedBytes.append(" + (i * ").append(size).append("));\n");
            nestedBytes.append("       }\n");
        }
    }
}
