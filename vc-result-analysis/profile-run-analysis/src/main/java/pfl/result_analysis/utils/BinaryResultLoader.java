package pfl.result_analysis.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.UnsynchronizedBufferedInputStream;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.ImmutableTriple;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;
import com.google.common.io.CountingInputStream;
import com.google.common.primitives.Ints;

import it.unimi.dsi.io.ByteBufferInputStream;

public class BinaryResultLoader
{
    public static Map<VCHashBytes, List<CustomUUID>> loadLoopIDIterIDMap(File path) throws IOException
    {
        FileInputStream file = new FileInputStream(path);
        InputStream fis;

        long fileSize = FileUtils.sizeOf(path);
        if (fileSize > 50 * 1024 * 1024)
        {
            FileChannel channel = file.getChannel();
            fis = ByteBufferInputStream.map(channel, FileChannel.MapMode.READ_ONLY);
        }
        else
        {
            fis = file;
        }
        Map<VCHashBytes, List<CustomUUID>> r;
        try
        {
            r = loadLoopIDIterIDMap(fis, fileSize);
        }
        catch (IOException e)
        {
            if (e instanceof MagicNumberErrorException)
            {
                e = new IOException("Magic Number Error: " + path, e);
            }
            else if (e instanceof FileCorruptedExcpetion)
            {
                e = new IOException("LoopIDIterIDMap corrupted: " + path, e);
            }
            throw e;
        }
        finally
        {
            fis.close();
            file.close();
        }
        return r;
    }

    public static Map<VCHashBytes, List<CustomUUID>> loadLoopIDIterIDMap_v2(File path, Map<Integer, String> execIDInverseMap) throws IOException
    {
        FileInputStream file = new FileInputStream(path);
        InputStream fis;

        long fileSize = FileUtils.sizeOf(path);
        if (fileSize > 50 * 1024 * 1024)
        {
            FileChannel channel = file.getChannel();
            fis = ByteBufferInputStream.map(channel, FileChannel.MapMode.READ_ONLY);
        }
        else
        {
            fis = file;
        }
        Map<VCHashBytes, List<CustomUUID>> r;
        try
        {
            r = loadLoopIDIterIDMap_v2(fis, fileSize, execIDInverseMap);
        }
        catch (IOException e)
        {
            if (e instanceof MagicNumberErrorException)
            {
                e = new IOException("Magic Number Error: " + path, e);
            }
            else if (e instanceof FileCorruptedExcpetion)
            {
                e = new IOException("LoopIDIterIDMap corrupted: " + path, e);
            }
            throw e;
        }
        finally
        {
            fis.close();
            file.close();
        }
        return r;
    }

    // fis closed by caller
    public static Map<VCHashBytes, List<CustomUUID>> loadLoopIDIterIDMap(InputStream fis, long fileSize) throws IOException
    {
        Map<VCHashBytes, List<CustomUUID>> r = new ConcurrentHashMap<>();
        UnsynchronizedBufferedInputStream bis = new UnsynchronizedBufferedInputStream.Builder().setInputStream(fis).setBufferSize(262144).get();
        CountingInputStream cis = new CountingInputStream(bis);
        int magicNumber = BinaryUtils.readInt(cis);
        if (magicNumber != 0x884832CB)
        {
            cis.close();
            bis.close();
            throw new MagicNumberErrorException();
        }
        int version = BinaryUtils.readInt(cis);
        assert version == 0x00000001;
        long mapSize = BinaryUtils.readLong(cis);
        for (int i = 0; i < mapSize; i++)
        {
            VCHashBytes loopKeyHash = VCHashBytes.wrap(BinaryUtils.readBytes(cis, 16));
            long valueSize = BinaryUtils.readLong(cis);
            List<CustomUUID> value = new ArrayList<>();
            long endPosition = cis.getCount() + valueSize * 16;
            if ((valueSize < 0) || (endPosition > fileSize) || (endPosition < 0))
            {
                cis.close();
                bis.close();
                throw new FileCorruptedExcpetion();
            }
            for (int j = 0; j < valueSize; j++)
            {
                CustomUUID iterID = BinaryUtils.readCustomUUID(cis);
                value.add(iterID);
            }
            r.put(loopKeyHash, value);
        }
        cis.close();
        bis.close();
        return r;
    }

    /*
     * Map<String, Collection<UUID>> loopIDIterIDMap
     * Format:
     * | 0x884832CB | 0x00000002 (Ver 2.0) | 64 bit Map length | key1: 32bit Mapped LoopHash | 64 bit Value List Length | value1: 128bit UUID | value2: 128bit UUID |
     * | key2: ... |
     */
    public static Map<VCHashBytes, List<CustomUUID>> loadLoopIDIterIDMap_v2(InputStream fis, long fileSize, Map<Integer, String> execIDInverseMap) throws IOException
    {
        Map<VCHashBytes, List<CustomUUID>> r = new ConcurrentHashMap<>();
        UnsynchronizedBufferedInputStream bis = new UnsynchronizedBufferedInputStream.Builder().setInputStream(fis).setBufferSize(262144).get();
        CountingInputStream cis = new CountingInputStream(bis);
        int magicNumber = BinaryUtils.readInt(cis);
        if (magicNumber != 0x884832CB)
        {
            cis.close();
            bis.close();
            throw new MagicNumberErrorException();
        }
        int version = BinaryUtils.readInt(cis);
        assert version == 0x00000002;
        long mapSize = BinaryUtils.readLong(cis);
        for (int i = 0; i < mapSize; i++)
        {
            int loopKeyHashMapped = BinaryUtils.readInt(cis);
            VCHashBytes loopKeyHash = VCHashBytes.wrap(HashCode.fromString(execIDInverseMap.get(loopKeyHashMapped)).asBytes());
            long valueSize = BinaryUtils.readLong(cis);
            List<CustomUUID> value = new ArrayList<>();
            long endPosition = cis.getCount() + valueSize * 16;
            if ((valueSize < 0) || (endPosition > fileSize) || (endPosition < 0))
            {
                cis.close();
                bis.close();
                throw new FileCorruptedExcpetion();
            }
            for (int j = 0; j < valueSize; j++)
            {
                CustomUUID iterID = BinaryUtils.readCustomUUID(cis);
                value.add(iterID);
            }
            r.put(loopKeyHash, value);
        }
        cis.close();
        bis.close();
        return r;
    }

    public static ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, Set<CustomUUID>, Set<VCHashBytes>> load_IterEventsMap_InjectionIter_ReachedInjectionIDs(File path)
            throws IOException
    {
        ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> tTriple = load_IterEventsMap_InjectionIterList_ReachedInjectionList(path);
        return ImmutableTriple.of(tTriple.getLeft(), new HashSet<>(tTriple.getMiddle()), new HashSet<>(tTriple.getRight()));
    }

    public static ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> load_IterEventsMap_InjectionIterList_ReachedInjectionList(File path)
            throws IOException
    {
        FileInputStream file = new FileInputStream(path);
        InputStream fis;
        long fileSize = FileUtils.sizeOf(path);
        if (fileSize > 50 * 1024 * 1024)
        {
            FileChannel channel = file.getChannel();
            fis = ByteBufferInputStream.map(channel, FileChannel.MapMode.READ_ONLY);
        }
        else
        {
            fis = file;
        }
        ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> r;
        try
        {
            r = load_IterEventsMap_InjectionIterList_ReachedInjectionList(fis, fileSize);
        }
        catch (IOException e)
        {
            if (e instanceof MagicNumberErrorException)
            {
                e = new IOException("Magic Number Error: " + path, e);
            }
            else if (e instanceof FileCorruptedExcpetion)
            {
                e = new IOException("IterEvents corrupted: " + path, e);
            }
            throw e;
        }
        finally
        {
            fis.close();
            file.close();
        }
        return r;
    }

    public static ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, Set<CustomUUID>, Set<VCHashBytes>> load_IterEventsMap_InjectionIter_ReachedInjectionIDs(InputStream fis,
            long fileSize)
            throws IOException
    {
        ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> tTriple = load_IterEventsMap_InjectionIterList_ReachedInjectionList(fis, fileSize);
        return ImmutableTriple.of(tTriple.getLeft(), new HashSet<>(tTriple.getMiddle()), new HashSet<>(tTriple.getRight()));
    }

    public static ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> load_IterEventsMap_InjectionIterList_ReachedInjectionList(
            InputStream fis, long fileSize)
            throws IOException
    {
        Map<CustomUUID, List<VCHashBytes>> r = new ConcurrentHashMap<>();
        UnsynchronizedBufferedInputStream bis = new UnsynchronizedBufferedInputStream.Builder().setInputStream(fis).setBufferSize(262144).get();
        CountingInputStream cis = new CountingInputStream(bis);
        List<CustomUUID> injectionIter = new ArrayList<>();
        List<VCHashBytes> reachedInjectionIDs = new ArrayList<>();
        int magicNumber = BinaryUtils.readInt(cis);
        if (magicNumber != 0x044832CB)
        {
            cis.close();
            bis.close();
            throw new MagicNumberErrorException();
        }
        int version = BinaryUtils.readInt(cis);
        assert version == 0x00000001;
        long mapSize = BinaryUtils.readLong(cis);
        for (int i = 0; i < mapSize; i++)
        {
            CustomUUID iterID = BinaryUtils.readCustomUUID(cis);
            long valueSize = BinaryUtils.readLong(cis);
            List<VCHashBytes> value = new ArrayList<>();
            long endPosition = cis.getCount() + valueSize * 21;
            if ((valueSize < 0) || (endPosition > fileSize) || (endPosition < 0))
            {
                cis.close();
                bis.close();
                throw new FileCorruptedExcpetion();
            }

            for (int j = 0; j < valueSize; j++)
            {
                byte[] type = BinaryUtils.readBytes(cis, 1);
                byte[] rawValue = BinaryUtils.readBytes(cis, 20);
                if (type[0] == 66) // "B".getBytes(StandardCharsets.UTF_8)[0]
                {
                    value.add(VCHashBytes.wrap(rawValue));
                }
                else if (type[0] == 73) // "I".getBytes(StandardCharsets.UTF_8)[0]
                {
                    injectionIter.add(iterID);
                    byte[] injectionIDRaw = Arrays.copyOfRange(rawValue, 0, 16);
                    reachedInjectionIDs.add(VCHashBytes.wrap(injectionIDRaw));
                    // value.add(VCHashBytes.wrap(injectionIDRaw));
                }
            }
            r.put(iterID, value);
        }
        cis.close();
        bis.close();
        return ImmutableTriple.of(r, injectionIter, reachedInjectionIDs);
    }

    public static ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> load_IterEventsMap_InjectionIterList_ReachedInjectionList_v2_simple(File path,
            Map<Integer, String> execIDInverseMap) throws IOException, ExecutionException
    {
        Map<CustomUUID, CustomUUID> parentIterIDMap = new HashMap<>();
        Map<CustomUUID, Long> iterIDTIDMap = new HashMap<>();
        return load_IterEventsMap_InjectionIterList_ReachedInjectionList_v2(path, execIDInverseMap, parentIterIDMap, iterIDTIDMap);
    }

    public static ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> load_IterEventsMap_InjectionIterList_ReachedInjectionList_v2_simple(
        InputStream fis, long fileSize, Map<Integer, String> execIDInverseMap)
        throws IOException, ExecutionException
    {
        Map<CustomUUID, CustomUUID> parentIterIDMap = new HashMap<>();
        Map<CustomUUID, Long> iterIDTIDMap = new HashMap<>();
        return load_IterEventsMap_InjectionIterList_ReachedInjectionList_v2(fis, fileSize, execIDInverseMap, parentIterIDMap, iterIDTIDMap);
    }

    public static ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> load_IterEventsMap_InjectionIterList_ReachedInjectionList_v2(File path,
            Map<Integer, String> execIDInverseMap, Map<CustomUUID, CustomUUID> out_parentIterIDMap, Map<CustomUUID, Long> out_iterIDTIDMap)
            throws IOException, ExecutionException
    {
        FileInputStream file = new FileInputStream(path);
        InputStream fis;
        long fileSize = FileUtils.sizeOf(path);
        if (fileSize > 50 * 1024 * 1024)
        {
            FileChannel channel = file.getChannel();
            fis = ByteBufferInputStream.map(channel, FileChannel.MapMode.READ_ONLY);
        }
        else
        {
            fis = file;
        }
        ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> r;
        try
        {
            r = load_IterEventsMap_InjectionIterList_ReachedInjectionList_v2(fis, fileSize, execIDInverseMap, out_parentIterIDMap, out_iterIDTIDMap);
        }
        catch (IOException e)
        {
            if (e instanceof MagicNumberErrorException)
            {
                e = new IOException("Magic Number Error: " + path, e);
            }
            else if (e instanceof FileCorruptedExcpetion)
            {
                e = new IOException("IterEvents corrupted: " + path, e);
            }
            throw e;
        }
        finally
        {
            fis.close();
            file.close();
        }
        return r;
    }

    /*
     * Map<UUID, List<IterEventBase>> iterEventsMap
     * Format:
     * | 0x044832CB | 0x00000002 (Ver 2.0) | 64 bit Map length | key1: 128bit Iter UUID | 128bit Parent Iter UUID | 64bit TID |
     * | 64 bit Value List Length | value1: 8bit Type (B/I/L) + 128bit ID(L) / 32bit Mapped ID (B/I) | value2 ... |
     * | key2: ... |
     */
    public static ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> load_IterEventsMap_InjectionIterList_ReachedInjectionList_v2(
            InputStream fis, long fileSize, Map<Integer, String> execIDInverseMap, Map<CustomUUID, CustomUUID> out_parentIterIDMap, Map<CustomUUID, Long> out_iterIDTIDMap)
            throws IOException, ExecutionException
    {
        Map<CustomUUID, List<VCHashBytes>> r = new ConcurrentHashMap<>();
        UnsynchronizedBufferedInputStream bis = new UnsynchronizedBufferedInputStream.Builder().setInputStream(fis).setBufferSize(262144).get();
        CountingInputStream cis = new CountingInputStream(bis);
        List<CustomUUID> injectionIter = new ArrayList<>();
        List<VCHashBytes> reachedInjectionIDs = new ArrayList<>();
        int magicNumber = BinaryUtils.readInt(cis);
        if (magicNumber != 0x044832CB)
        {
            cis.close();
            bis.close();
            throw new MagicNumberErrorException();
        }
        int version = BinaryUtils.readInt(cis);
        assert version == 0x00000002;
        long mapSize = BinaryUtils.readLong(cis);
        LoadingCache<Integer, byte[]> branchIDCache = CacheBuilder.newBuilder().softValues().build(new CacheLoader<Integer, byte[]>() 
        {
            @Override
            public byte[] load(Integer branchIDMapped) throws Exception
            {
                String branchID = execIDInverseMap.get(branchIDMapped);
                if (branchID == null) System.err.println("Branch ID not found: " + branchIDMapped);
                String[] split = branchID.split("-");
                ByteBuffer buf = ByteBuffer.allocate(20);
                buf.put(HashCode.fromString(split[0]).asBytes());
                buf.putInt(Integer.valueOf(split[1]));
                return buf.array();
            }
        });
        for (int i = 0; i < mapSize; i++)
        {
            CustomUUID iterID = BinaryUtils.readCustomUUID(cis);
            CustomUUID parentIterID = BinaryUtils.readCustomUUID(cis);
            long iterTID = BinaryUtils.readLong(cis);
            long valueSize = BinaryUtils.readLong(cis);
            List<VCHashBytes> value = new ArrayList<>();
            long endPosition = cis.getCount() + valueSize * 5;
            if ((valueSize < 0) || (endPosition > fileSize) || (endPosition < 0))
            {
                System.out.println("Value Size: " + valueSize);
                System.out.println("End Position: " + endPosition);
                System.out.println("File Size: " + fileSize);
                System.out.println("Current Position: " + cis.getCount());
                cis.close();
                bis.close();
                throw new FileCorruptedExcpetion();
            }
            out_parentIterIDMap.put(iterID, parentIterID);
            out_iterIDTIDMap.put(iterID, iterTID);

            for (int j = 0; j < valueSize; j++)
            {
                byte[] type = BinaryUtils.readBytes(cis, 1);
                if (type[0] == 66) // "B".getBytes(StandardCharsets.UTF_8)[0]
                {
                    int branchIDMapped = BinaryUtils.readInt(cis);
                    value.add(VCHashBytes.wrap(branchIDCache.get(branchIDMapped)));
                }
                else if (type[0] == 73) // "I".getBytes(StandardCharsets.UTF_8)[0]
                {
                    injectionIter.add(iterID);
                    int injectionIDMapped = BinaryUtils.readInt(cis);
                    String injectionID = execIDInverseMap.get(injectionIDMapped);
                    byte[] injectionIDRaw = HashCode.fromString(injectionID).asBytes();
                    reachedInjectionIDs.add(VCHashBytes.wrap(injectionIDRaw));
                    // value.add(VCHashBytes.wrap(injectionIDRaw));
                }
                else if (type[0] == 76) // "L"
                {
                    BinaryUtils.readBytes(cis, 16);
                }
                if (cis.getCount() > fileSize)
                {
                    cis.close();
                    throw new FileCorruptedExcpetion();
                }
            }
            r.put(iterID, value);
        }
        cis.close();
        bis.close();
        return ImmutableTriple.of(r, injectionIter, reachedInjectionIDs);
    }

    public static Map<VCHashBytes, Set<LoopSignature>> buildLoopSignatureSingleIter(Map<VCHashBytes, List<CustomUUID>> loopIDIterIDMap,
            Map<CustomUUID, List<VCHashBytes>> iterEventsMap) throws IOException, ExecutionException, InterruptedException
    {
        Map<VCHashBytes, Set<LoopSignature>> loopSignatures = new ConcurrentHashMap<>(); // LoopID -> Set([BranchEvents])
        List<ImmutablePair<VCHashBytes, CustomUUID>> workload = new ArrayList<>();
        loopIDIterIDMap.keySet().forEach(loopKey -> loopSignatures.computeIfAbsent(loopKey, k -> ConcurrentHashMap.newKeySet()));
        loopIDIterIDMap.keySet().forEach(loopKey -> loopIDIterIDMap.get(loopKey).forEach(iterID -> workload.add(ImmutablePair.of(loopKey, iterID))));
        Utils.commonPoolSyncRun(() ->
        {
            workload.parallelStream().forEach(work ->
            {
                VCHashBytes loopKey = work.getLeft();
                CustomUUID iterID = work.getRight();
                List<VCHashBytes> iterSignature = iterEventsMap.get(iterID);
                if (iterSignature == null) return;
                loopSignatures.get(loopKey).add(new LoopSignature(iterSignature));
            });
        });
        return loopSignatures;
    }

    public static Map<VCHashBytes, Map<ImmutableList<String>, Set<LoopSignature>>> buildLoopSignatureByCallsite(Map<VCHashBytes, List<CustomUUID>> loopIDIterIDMap,
            Map<CustomUUID, List<VCHashBytes>> iterEventsMap, Map<CustomUUID, int[]> iterIDStackMethodIdMap, Map<Integer, String> methodIdxMap)
            throws IOException, ExecutionException, InterruptedException
    {
        Map<VCHashBytes, Map<ImmutableList<String>, Set<LoopSignature>>> loopSignatures = new ConcurrentHashMap<>(); // LoopID -> {[CallSite]: Set([BranchEvents])}
        Map<CustomUUID, VCHashBytes> iterIDLoopIDMap = new ConcurrentHashMap<>();
        for (VCHashBytes loopKey : loopIDIterIDMap.keySet())
        {
            loopIDIterIDMap.get(loopKey).forEach(iterID -> iterIDLoopIDMap.put(iterID, loopKey));
        }
        LoadingCache<List<Integer>, ImmutableList<String>> callsiteStrCache = CacheBuilder.newBuilder().weakValues().build(new CacheLoader<List<Integer>, ImmutableList<String>>()
        {
            @Override
            public ImmutableList<String> load(List<Integer> stackMethodId)
            {
                ImmutableList.Builder<String> stackMethodKeyBuilder = ImmutableList.builder();
                stackMethodId.stream().map(e -> methodIdxMap.getOrDefault(e, "NONE")).forEachOrdered(stackMethodKeyBuilder::add);
                return stackMethodKeyBuilder.build();
            }
        });
        Utils.commonPoolSyncRun(() ->
        {
            iterIDStackMethodIdMap.keySet().parallelStream().forEach(iterID ->
            {
                VCHashBytes loopKey = iterIDLoopIDMap.get(iterID);
                if (loopKey == null) return;
                List<VCHashBytes> iterSignature = iterEventsMap.get(iterID);
                if (Objects.isNull(iterSignature)) return;
                ImmutableList<String> callSiteList = callsiteStrCache.getUnchecked(Ints.asList(iterIDStackMethodIdMap.get(iterID)));

                loopSignatures.computeIfAbsent(loopKey, k -> new ConcurrentHashMap<>()).computeIfAbsent(callSiteList, k -> ConcurrentHashMap.newKeySet())
                        .add(new LoopSignature(iterSignature));
            });
        });
        callsiteStrCache.invalidateAll();
        return loopSignatures;
    }

    public static Map<CustomUUID, int[]> loadIterIDStackMethodIdMap(File path) throws IOException
    {
        FileInputStream file = new FileInputStream(path);
        InputStream fis;
        long fileSize = FileUtils.sizeOf(path);
        if (fileSize > 50 * 1024 * 1024)
        {
            FileChannel channel = file.getChannel();
            fis = ByteBufferInputStream.map(channel, FileChannel.MapMode.READ_ONLY);
        }
        else
        {
            fis = file;
        }
        Map<CustomUUID, int[]> iterIDStackMethodIdMap;
        try
        {
            iterIDStackMethodIdMap = loadIterIDStackMethodIdMap(fis, fileSize);
        }
        catch (IOException e)
        {
            if (e instanceof MagicNumberErrorException)
            {
                e = new IOException("Magic Number Error: " + path, e);
            }
            else if (e instanceof FileCorruptedExcpetion)
            {
                e = new IOException("IterIDStackMethodIdMap corrupted: " + path, e);
            }
            throw e;
        }
        finally
        {
            fis.close();
            file.close();
        }
        return iterIDStackMethodIdMap;
    }

    public static Map<CustomUUID, int[]> loadIterIDStackMethodIdMap(InputStream fis, long fileSize) throws IOException
    {
        UnsynchronizedBufferedInputStream bis = new UnsynchronizedBufferedInputStream.Builder().setInputStream(fis).setBufferSize(262144).get();
        CountingInputStream cis = new CountingInputStream(bis);
        int magicNumber = BinaryUtils.readInt(cis);
        if (magicNumber != 0x334832CB)
        {
            cis.close();
            bis.close();
            throw new MagicNumberErrorException();
        }
        int version = BinaryUtils.readInt(cis);
        assert version == 0x00000001;
        long mapSize = BinaryUtils.readLong(cis);
        int callerFrameCount = BinaryUtils.readInt(cis);
        Map<CustomUUID, int[]> iterIDStackMethodIdMap = new ConcurrentHashMap<>();
        for (int i = 0; i < mapSize; i++)
        {
            CustomUUID iterID = BinaryUtils.readCustomUUID(cis);
            long endPosition = cis.getCount() + callerFrameCount * 4;
            if ((endPosition > fileSize) || (endPosition < 0))
            {
                cis.close();
                bis.close();
                throw new FileCorruptedExcpetion();
            }
            int[] value = BinaryUtils.readyIntArray(cis, callerFrameCount);
            iterIDStackMethodIdMap.put(iterID, value);
        }
        cis.close();
        bis.close();
        return iterIDStackMethodIdMap;
    }
}
