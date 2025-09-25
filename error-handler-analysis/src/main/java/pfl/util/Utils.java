package pfl.util;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Streams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Paths;

import com.ibm.wala.cfg.IBasicBlock;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.Selector;
import com.ibm.wala.types.TypeReference;

import org.apache.commons.lang3.tuple.ImmutablePair;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IntSummaryStatistics;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class Utils
{
    public static <T> Iterable<T> toIterable(final Iterator<T> iter)
    {
        return new Iterable<T>()
        {
            public Iterator<T> iterator()
            {
                return iter;
            }
        };
    }

    public static <T> T deserialize(String file) throws IOException, ClassNotFoundException
    {
        File f = new File(file);
        return deserialize(f);
    }

    public static <T> T deserialize(File file) throws IOException, ClassNotFoundException
    {
        FileInputStream fis = new FileInputStream(file);
        ObjectInputStream ois = new ObjectInputStream(fis);
        T obj = (T) ois.readObject();
        fis.close();
        return obj;
    }

    public static void serialize(Object o, File file) throws IOException
    {
        FileOutputStream fos = new FileOutputStream(file);
        ObjectOutputStream oos = new ObjectOutputStream(fos);
        oos.writeObject(o);
        oos.close();
        fos.close();
    }

    public static void serialize(Object o, String file) throws IOException
    {
        File f = new File(file);
        serialize(o, f);
    }

    public static void setFinalStatic(Field field, Object newValue) throws Exception
    {
        field.setAccessible(true);

        Field modifiersField = field.getClass().getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        // System.out.println(field.getModifiers());
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        // System.out.println(field.getModifiers());
        field.set(null, newValue);
        // System.out.println(field.get(null));
    }

    public static boolean insideJRELibrary(String declaringClassName)
    {
        if (declaringClassName.contains("Ljava/") || declaringClassName.contains("Ljavax/"))
            return true;
        else
            return false;
    }

    public static boolean insideJRELibrary(IClass c)
    {
        return insideJRELibrary(c.getName().toString());
    }

    public static boolean insideJRELibrary(TypeReference c)
    {
        return insideJRELibrary(c.getName().toString());
    }

    public static String getShortMethodName(IMethod m)
    {
        return getShortMethodName(m.getReference());
    }

    public static String getShortMethodName(MethodReference m)
    {
        return Iterables.getLast(Arrays.asList(getFullMethodName(m).split("\\.")));
    }

    public static String getFullMethodName(MethodReference m)
    {
        String signature = m.getSignature();
        String[] sigParts = signature.split("[\\(\\)]");
        return sigParts[0];
    }

    public static String getFullMethodName(IMethod m)
    {
        return getFullMethodName(m.getReference());
    }

    public static String getShortClassName(IClass c)
    {
        return Iterables.getLast(Arrays.asList(getFullClassName(c).split("\\.")));
    }

    public static String toWalaFullClassName(String dotSepClassName)
    {
        if (dotSepClassName.contains("$")) return "L" + dotSepClassName.replace('.', '/'); // From WALA output, we just reuse it
        List<String> packageNames = new ArrayList<>();
        List<String> classNames = new ArrayList<>();
        boolean hasClassNameStarted = false;
        boolean cannotUseHeuristics = false;
        try 
        {
            for (String split: dotSepClassName.split("\\."))
            {   
                if (Character.isLowerCase(split.charAt(0)))
                {
                    if (hasClassNameStarted) 
                    {
                        cannotUseHeuristics = true;
                        break;
                    }
                    packageNames.add(split);
                }
                else 
                {
                    hasClassNameStarted = true;
                    classNames.add(split);
                }
            }
        }
        catch (IndexOutOfBoundsException e)
        {
            cannotUseHeuristics = true;
        }
        if (cannotUseHeuristics)
        {   
            System.out.println("WARNING: Cannot assume package and class name: " + dotSepClassName);
            return "L" + dotSepClassName.replace('.', '/');
        }
        else 
        {
            return "L" + String.join("/", packageNames) + "/" + String.join("$", classNames);
        }
    }

    public static String getFullClassName(TypeReference tr)
    {
        return tr.getName().toString().substring(1).replace('/', '.');
    }

    public static String getFullClassName(IClass c)
    {
        return getFullClassName(c.getReference());
    }

    public static boolean isRunnable(IClass c)
    {
        if (getFullClassName(c).equals("java.lang.Runnable"))
            return true;
        for (IClass i : c.getAllImplementedInterfaces())
        {
            if (getFullClassName(i).equals("java.lang.Runnable"))
                return true;
        }
        return false;
    }

    public static boolean isSameField(FieldReference lhs, FieldReference rhs)
    {
        if (lhs.getSignature().equals(rhs.getSignature()))
            return true;
        else
            return false;
    }

    public static boolean isSameMethod(MethodReference lhs, MethodReference rhs)
    {
        if (lhs.getSignature().equals(rhs.getSignature()))
            return true;
        else
            return false;
    }

    public static boolean isSameMethod(IMethod lhs, IMethod rhs)
    {
        return isSameMethod(lhs.getReference(), rhs.getReference());
    }

    // This is to solve the problem that when the call is a Interface call, and WALA gives us
    // the actual fuction. AspectJ cannot capture calls to the actual function if the declaration is an interface call.
    public static CallSiteReference findingMatchingCall(List<CallSiteReference> calls, CGNode target)
    {
        String targetMethodName = getShortMethodName(target.getMethod());
        for (CallSiteReference csr : calls)
        {
            String callName = getShortMethodName(csr.getDeclaredTarget());
            if (callName.equals(targetMethodName))
                return csr; // TODO: We only find the first match. If it causes problem, need to change it.
        }
        return null; // TODO: This should not happen.
    }

    public static List<CGNode> findCGNodeByName(CallGraph cg, String pattern)
    {
        List<CGNode> ret = new ArrayList<>();
        for (CGNode cgn : cg)
        {
            if (getFullMethodName(cgn.getMethod()).contains(pattern))
            {
                ret.add(cgn);
            }
        }
        return ret;
    }

    public static String parseWalaTypeString(String tyStr)
    {
        switch (tyStr)
        {
            case "V":
            case "B":
            case "C":
            case "D":
            case "F":
            case "I":
            case "J":
            case "S":
            case "Z":
                return convertPrimitiveFieldType(tyStr);
            default:
                if ((tyStr.length() == 2) && (tyStr.startsWith("[")))
                {
                    // Primitive array
                    return convertPrimitiveFieldType(tyStr.substring((1))) + "[]";
                }
                else if (tyStr.startsWith("L"))
                {
                    // Class
                    return tyStr.substring(1).replace('/', '.');
                }
                else if (tyStr.startsWith("[L"))
                {
                    // Class Array
                    return tyStr.substring(2).replace('/', '.') + "[]";
                }
                else
                {
                    return "##TYPE_NOT_SUPPORTED##"; // TODO: Not yet implemented
                }
        }
    }

    public static String convertPrimitiveFieldType(String tyStr)
    {
        switch (tyStr)
        {
            case "V":
                return "void";
            case "B":
                return "byte";
            case "C":
                return "char";
            case "D":
                return "double";
            case "F":
                return "float";
            case "I":
                return "int";
            case "J":
                return "long";
            case "S":
                return "short";
            case "Z":
                return "boolean";
            default:
                return "Not a primitive type";
        }
    }

    public static String primitiveToBoxed(String tyStr)
    {
        switch (tyStr)
        {
            case "V":
                return "java.lang.Void";
            case "B":
                return "java.lang.Byte";
            case "C":
                return "java.lang.Character";
            case "D":
                return "java.lang.Double";
            case "F":
                return "java.lang.Float";
            case "I":
                return "java.lang.Integer";
            case "J":
                return "java.lang.Long";
            case "S":
                return "java.lang.Short";
            case "Z":
                return "java.lang.Boolean";
            default:
                return "java.lang.Object";
        }
    }

    public static Map<String, Object> getAspectJMethodProperties(MethodReference m)
    {
        String name = Utils.toAspectJMethodStr(Utils.getFullMethodName(m));
        String returnType = Utils.toAspectJTypeStr(Utils.parseWalaTypeString(m.getReturnType().getName().toString()));
        List<String> argsType = new ArrayList<>();
        for (int i = 0; i < m.getNumberOfParameters(); i++)
        {
            // AspectJ capture Class$InnerClass as Class.InnerClass in the arugment list
            String typeStr = Utils.toAspectJTypeStr(Utils.parseWalaTypeString(m.getParameterType(i).getName().toString()));
            argsType.add(typeStr);
        }
        Map<String, Object> ret = new HashMap<>();
        ret.put("DeclaredName", name);
        ret.put("Arguments", argsType);
        ret.put("Return", returnType);
        return ret;
    }

    public static Map<String, Object> getAspectJMethodProperties(IMethod m)
    {
        return getAspectJMethodProperties(m.getReference());
    }

    // Also for class name
    public static String toAspectJTypeStr(String walaTypeStr)
    {
        List<String> parts = Lists.newArrayList(walaTypeStr.split("\\."));
        String className = Iterables.getLast(parts);
        if (!className.matches(".*\\$[0-9]+.*")) // We don't do anything about anonymous inner class
        {
            // We fix MigrationManager$MigrationsSerializer but not ClusteringIndexSliceFilter$1FilterNotIndexed
            className = className.replace('$', '.');
        }
        else
        {
            return walaTypeStr;
        }
        parts.set(parts.size() - 1, className);
        return String.join(".", parts);
    }

    public static String toAspectJMethodStr(String walaFullMethodStr)
    {
        int methodSplitPos = walaFullMethodStr.lastIndexOf(".");
        if (methodSplitPos == -1) return walaFullMethodStr;
        return toAspectJTypeStr(walaFullMethodStr.substring(0, methodSplitPos)) + walaFullMethodStr.substring(methodSplitPos);
    }

    public static <T> Set<T> scalaToSet(Collection<Object> c)
    {
        return c.stream().map(e -> (T) e).collect(Collectors.toSet());
    }

    public static int getSrcLineNumberBySSAInst(IR ir, int indexOfSSAInst)
    {
        try
        {
            IBytecodeMethod method = (IBytecodeMethod) ir.getMethod();
            int bytecodeIndex = method.getBytecodeIndex(indexOfSSAInst);
            int sourceLineNum = method.getLineNumber(bytecodeIndex);
            return sourceLineNum;
        }
        catch (InvalidClassFileException e)
        {
            return -1;
        }
    }

    public static List<ImmutablePair<String, Integer>> getAllInvokeTargetAndLineNumberInBB(ISSABasicBlock bb, IR ir)
    {
        SSAInstruction[] insts = ir.getInstructions();
        List<ImmutablePair<String, Integer>> ret = new ArrayList<>();
        for (int i = bb.getFirstInstructionIndex(); i <= bb.getLastInstructionIndex(); i++)
        {
            if (insts[i] instanceof SSAInvokeInstruction)
            {
                SSAInvokeInstruction inst = (SSAInvokeInstruction) insts[i];
                int srcLineNum = Utils.getSrcLineNumberBySSAInst(ir, i);
                if (srcLineNum != -1)
                {
                    ret.add(ImmutablePair.of(Utils.getFullMethodName(inst.getDeclaredTarget()), srcLineNum));
                }
            }
        }
        Collections.sort(ret, (a, b) -> a.getRight().compareTo(b.getRight()));
        return ret;
    }

    public static SSAInstruction getDefInstruction(IR methodIR, SSAInstruction inst, int defIdx)
    {
        int iIndex = inst.iIndex();
        SSAInstruction originalInst =  methodIR.getInstructions()[iIndex];
        DefUse du = new DefUse(methodIR);
        SSAInstruction defInst = du.getDef(originalInst.getUse(defIdx));
        return defInst;
    }

    public static SSAInstruction getDefInstruction_Throw(IR methodIR, SSAInstruction inst, int defIdx)
    {
        int iIndex = inst.iIndex();
        SSAThrowInstruction originalInst = (SSAThrowInstruction) methodIR.getInstructions()[iIndex];
        DefUse du = new DefUse(methodIR);
        SSAInstruction defInst = du.getDef(originalInst.getUse(defIdx));
        return defInst;
    }

    public static List<TypeReference> getMethodParamTypes(IMethod m)
    {
        List<TypeReference> r = new LinkedList<>();
        for (int i = 0; i < m.getNumberOfParameters(); i++)
        {
            r.add(m.getParameterType(i));
        }
        return r;
    }

    public static List<TypeReference> getMethodParamTypesSkipped(IMethod m, int firstSkip)
    {
        List<TypeReference> r = new LinkedList<>();
        for (int i = firstSkip; i < m.getNumberOfParameters(); i++)
        {
            r.add(m.getParameterType(i));
        }
        return r;
    }

    public static boolean isStmtType(SSAInstruction inst, Class type)
    {
        return type.isInstance(inst);
    }

    public static boolean basicBlockContainsStmtStype(IBasicBlock<SSAInstruction> bb, Class type)
    {
        return Streams.stream(bb).anyMatch(inst -> isStmtType(inst, type));
    }

    public static boolean basicBlockContainsStmtsPredicate(IBasicBlock<SSAInstruction> bb, Predicate<SSAInstruction> predicate)
    {
        return Streams.stream(bb).filter(Objects::nonNull).anyMatch(predicate);
    }

    public static IMethod lookupMethod(ClassHierarchy cha, MethodReference mRef)
    {
        if (mRef == null) return null;
        TypeReference defClassRef = mRef.getDeclaringClass();
        IClass defClass = cha.lookupClass(defClassRef);
        if (defClass == null) return null;
        Selector sel = new Selector(mRef.getName(), mRef.getDescriptor());
        return defClass.getMethod(sel);
    }

    public static IField lookupField(ClassHierarchy cha, FieldReference fRef)
    {
        if (fRef == null) return null;
        TypeReference defClassRef = fRef.getDeclaringClass();
        IClass defClass = cha.lookupClass(defClassRef);
        if (defClass == null) return null;
        return defClass.getField(fRef.getName());
    }

    public static boolean inMethodRange(IR methodIR, int lineNo)
    {
        IBytecodeMethod method = (IBytecodeMethod) methodIR.getMethod();
        SSAInstruction[] insts = methodIR.getInstructions();
        IntSummaryStatistics stat = Arrays.stream(insts).filter(Objects::nonNull).filter(inst -> inst.iIndex() > 0).map(inst -> getSrcLineNumberBySSAInst(methodIR, inst.iIndex()))
                .collect(Collectors.summarizingInt(Integer::intValue));
        return (lineNo >= stat.getMin()) && (lineNo <= stat.getMax());
    }

    public static boolean inCodeBlockRange(IR methodIR, Set<? extends IBasicBlock<SSAInstruction>> codeBlock, int lineNo)
    {
        IntSummaryStatistics stat = getCodeBlockRange(methodIR, codeBlock);
        return (lineNo >= stat.getMin()) && (lineNo <= stat.getMax());
    }

    public static IntSummaryStatistics getCodeBlockRange(IR methodIR, Set<? extends IBasicBlock<SSAInstruction>> codeBlock)
    {
        IBytecodeMethod method = (IBytecodeMethod) methodIR.getMethod();
        return codeBlock.stream().map(bb -> StreamSupport.stream(bb.spliterator(), false)).flatMap(Function.identity()).filter(Objects::nonNull).filter(inst -> inst.iIndex() > 0)
                .map(inst -> getSrcLineNumberBySSAInst(methodIR, inst.iIndex())).collect(Collectors.summarizingInt(Integer::intValue));
    }

    public static IntSummaryStatistics getMethodIRLineRange(IR methodIR)
    {
        return Arrays.stream(methodIR.getInstructions()).filter(Objects::nonNull).map(inst -> getSrcLineNumberBySSAInst(methodIR, inst.iIndex()))
                .collect(Collectors.summarizingInt(Integer::intValue));
    }

    // something like: com.foo.bar.createLargeOrder(IILjava.lang.String;SLjava.sql.Date;)Ljava.lang.Integer;
    public static MethodReference walaSignatureStrToMethodReference(ClassLoaderReference clRef, String signatureString)
    {
        String[] splits = signatureString.split("\\(");
        String descriptor = '(' + splits[1];
        String[] splits2 = splits[0].split("\\.");
        String methodName = splits2[splits2.length - 1];
        String className = String.join(".", Arrays.copyOf(splits2, splits2.length - 1));
        TypeReference tr = TypeReference.find(clRef, Utils.toWalaFullClassName(className));
        if (tr == null) return null;
        return MethodReference.findOrCreate(tr, methodName, descriptor);
    }

    public static <T> T readJson(String path) throws IOException
    {
        Type type = new TypeToken<T>(){}.getType();
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (BufferedReader r = Files.newBufferedReader(Paths.get(path)))
        {
            return gson.fromJson(r, type);
        }
    }

    // This includes all instructions including Phi and Pi
    // public static SSAInstruction[] getAllInstructionsIncludingPhiPi(IR methodIR)
    // {
    //     SSAInstruction[] insts = methodIR.getInstructions();
    //     for (SSAInstruction inst: Utils.toIterable(methodIR.iterateAllInstructions()))
    //     {
    //         if (inst.iIndex() < 0)
    //         {
    //             System.out.println("++++" + inst);
    //             continue;
    //         }
    //         insts[inst.iIndex()] = inst;
    //     }
    //     return insts;
    // }
}
