package pfl.util;

import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;

import edu.colorado.walautil.LoopUtil;

public class LoopUtilSynchronizedWrapper 
{  
    static Object lock = new Object();

    public static scala.collection.immutable.Set<Object> getLoopHeaders(IR ir)
    {
        scala.collection.immutable.Set<Object> result;
        synchronized (lock)
        {
            result = LoopUtil.getLoopHeaders(ir);
        }
        return result;
    }

    public static scala.collection.immutable.Set<ISSABasicBlock> getLoopBody(ISSABasicBlock header, IR ir) 
    {
        scala.collection.immutable.Set<ISSABasicBlock> result;
        synchronized (lock)
        {
            result = LoopUtil.getLoopBody(header, ir);
        }
        return result;
    }
}
