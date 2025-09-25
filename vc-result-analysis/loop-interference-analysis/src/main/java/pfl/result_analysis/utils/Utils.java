package pfl.result_analysis.utils;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.io.Reader;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.text.similarity.EditDistance;
import org.apache.commons.text.similarity.LevenshteinDistance;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

public class Utils 
{
    public static <T> T readJson(String path) throws FileNotFoundException, IOException
    {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Type retType = new TypeToken<T>()
        {
        }.getType();
        T ret;
        try (FileReader fr = new FileReader(path);
             BufferedReader br = new BufferedReader(fr);)
        {
            ret = gson.fromJson(br, retType);
        }
        return ret;
    }    

    public static <T> T readJson(String path, Type ty) throws IOException
    {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        T ret;
        try (FileReader fr = new FileReader(path);
             BufferedReader br = new BufferedReader(fr);)
        {
            ret = gson.fromJson(br, ty);
        }
        return ret;
    }

    public static <T> void dumpObj(T obj, String path) throws IOException
    {
        try (FileOutputStream fos = new FileOutputStream(path); 
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(bos);)
        {
            oos.writeObject(obj);
            oos.close();
            bos.close();
            fos.close();
        }
    }

    // something like: com.foo.bar.createLargeOrder(IILjava.lang.String;SLjava.sql.Date;)Ljava.lang.Integer;
    public static String walaSignatureStrToDotSeparatedFullMethodName(String signatureString)
    {
        String[] splits = signatureString.split("\\(");
        return splits[0];
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
        return "L" + dotSepClassName.replace('.', '/');
    }

    public static String getFullClassName(TypeReference tr)
    {
        return tr.getName().toString().substring(1).replace('/', '.');
    }

    public static String getFullClassName(IClass c)
    {
        return getFullClassName(c.getReference());
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

    public static IntSummaryStatistics getMethodIRLineRange(IR methodIR)
    {
        return Arrays.stream(methodIR.getInstructions()).filter(Objects::nonNull).map(inst -> getSrcLineNumberBySSAInst(methodIR, inst.iIndex()))
                .collect(Collectors.summarizingInt(Integer::intValue));
    }

    // Something like: org.apache.hadoop.ipc.RetryCache.newEntry(Ljava/lang/Object;J)Lorg/apache/hadoop/ipc/RetryCache$CacheEntryWithPayload;
    public static String mapWalaFullMethodSignatureToDotSeparatedMethodName(String walaMethodName)
    {
        String[] split = walaMethodName.split("\\(");
        return split[0];
    }

    public static VCHashBytes toVCHashBytes(LoopHash loopHash)
    {
        return VCHashBytes.wrap(loopHash.rawHashCode.asBytes());
    }

    public static LoopHash toLoopHash(VCHashBytes vcHashBytes)
    {
        return LoopHash.wrap(vcHashBytes.toString());
    }

    public static int getEditDistance(LoopSignature left, LoopSignature right)
    {
        StringBuilder sbL = new StringBuilder();
        StringBuilder sbR = new StringBuilder();
        BiMap<VCHashBytes, Character> sigToCharMapper = HashBiMap.create();
        char charCounter = 1;
        for (VCHashBytes sig: left.rawSignature)
        {
            Character mappedChar = sigToCharMapper.get(sig);
            if (!sigToCharMapper.containsKey(sig))
            {
                sigToCharMapper.put(sig, charCounter);
                charCounter++;
                mappedChar = sigToCharMapper.get(sig);
            }
            sbL.append(mappedChar);
        }
        for (VCHashBytes sig: right.rawSignature)
        {
            Character mappedChar = sigToCharMapper.get(sig);
            if (!sigToCharMapper.containsKey(sig))
            {
                sigToCharMapper.put(sig, charCounter);
                charCounter++;
                mappedChar = sigToCharMapper.get(sig);
            }
            sbR.append(mappedChar);
        }
        EditDistance<Integer> editDistance = LevenshteinDistance.getDefaultInstance();
        return editDistance.apply(sbL.toString(), sbR.toString());
    }

    public static boolean loopSigHasOnlyAllowedDiff(LoopSignature longer, LoopSignature shorter, Set<VCHashBytes> allowedDiff)
    {
        int l1 = longer.rawSignature.size();
        int l2 = shorter.rawSignature.size();
        if (l1 < l2) return false;

        // Optimization
        Set<VCHashBytes> longerSet = new HashSet<>(longer.rawSignature);
        Set<VCHashBytes> shorterSet = new HashSet<>(shorter.rawSignature);
        Set<VCHashBytes> symmetricDiff = Sets.symmetricDifference(longerSet, shorterSet);
        if (!allowedDiff.containsAll(symmetricDiff)) return false;
        
        int i = 0, j = 0;
        Set<VCHashBytes> actualDiff = new HashSet<>();
        while ((i < l1) && (j < l2))
        {
            if (Objects.equals(longer.rawSignature.get(i), shorter.rawSignature.get(j)))
            {   // shorter[j] == longer[i]
                j++;
            }
            else // Diff is Longer[i]
            {
                actualDiff.add(longer.rawSignature.get(i));
            }
            // If shorter[j] != longer[i], only skip the longer[i]
            i++;
        }
        if (j != l2) return false;

        // // Sometimes, maybe the if-branch is already recorded
        // if (allowedDiff.containsAll(actualDiff) && (actualDiff.size() > 0))
        // {
        //     System.out.println(longer.rawSignature);
        //     System.out.println("--------");
        //     System.out.println(shorter.rawSignature);
        //     System.out.println("========");
        //     System.out.println(allowedDiff);
        //     System.exit(0);
        // }
        return allowedDiff.containsAll(actualDiff) && (actualDiff.size() > 0);
    }

    public static <T> List<T> getEvenlyIndexedSublist(List<T> l)
    {
        List<T> r = new ArrayList<>();
        for (int i = 0; i < l.size(); i++)
        {
            if (i % 2 == 0) r.add(l.get(i));
        }
        return r;
    }
}
