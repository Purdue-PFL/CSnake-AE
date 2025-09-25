package pfl.graph;

import com.google.common.collect.Streams;
import com.ibm.wala.cfg.AbstractCFG;
import com.ibm.wala.cfg.IBasicBlock;

/*
 * Copyright (c) 2002 - 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 */

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInstructionFactory;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAMonitorInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.util.collections.ArrayIterator;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.graph.GraphIntegrity;
import com.ibm.wala.util.graph.GraphIntegrity.UnsoundGraphException;
import com.ibm.wala.util.graph.impl.NodeWithNumber;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

/**
 * A {@link ControlFlowGraph} computed from a set of {@link SSAInstruction} instructions.
 *
 * <p>
 * This is a funny CFG ... we assume that there are always fallthru edges, even from throws and
 * returns. It is extremely fragile and unsuited for flow-sensitive analysis. Someday this should be
 * nuked.
 * NOTE: This one lacks proper edges for exception handling.
 */
public class InducedNoExceptionCFG extends AbstractCFG<SSAInstruction, InducedNoExceptionCFG.BasicBlock>
{

    private static final boolean DEBUG = false;

    /** A partial map from Instruction -&gt; BasicBlock */
    private final BasicBlock[] i2block;

    private final Context context;

    private final SSAInstruction[] instructions;

    /**
     * TODO: we do not yet support induced CFGS with exception handlers.
     *
     * <p>
     * NOTE: SIDE EFFECT!!! ... nulls out phi instructions and pi instructions in the instruction
     * array!
     *
     * @throws IllegalArgumentException
     *             if instructions is null
     */
    public InducedNoExceptionCFG(SSAInstruction[] instructions, IMethod method, Context context)
    {
        super(method);
        if (instructions == null)
        {
            throw new IllegalArgumentException("instructions is null");
        }
        if (method == null)
        {
            throw new IllegalArgumentException("method is null");
        }
        this.context = context;
        this.instructions = instructions;
        if (DEBUG)
        {
            System.err.println(("compute InducedNoExceptionCFG: " + method));
        }
        i2block = new BasicBlock[instructions.length];
        if (instructions.length == 0)
        {
            makeEmptyBlocks();
        }
        else
        {
            makeBasicBlocks();
        }
        init();
        computeEdges();

        if (DEBUG)
        {
            try
            {
                GraphIntegrity.check(this);
            }
            catch (UnsoundGraphException e)
            {
                e.printStackTrace();
                Assertions.UNREACHABLE();
            }
        }
    }

    @Override
    public int hashCode()
    {
        return context.hashCode() ^ getMethod().hashCode();
    }

    @Override
    public boolean equals(Object o)
    {
        return (o instanceof InducedNoExceptionCFG)
                && getMethod().equals(((InducedNoExceptionCFG) o).getMethod())
                && context.equals(((InducedNoExceptionCFG) o).context);
    }

    @Override
    public SSAInstruction[] getInstructions()
    {
        return instructions;
    }

    /** Compute outgoing edges in the control flow graph. */
    private void computeEdges()
    {
        for (BasicBlock b : this)
        {
            if (b.equals(exit()))
                continue;
            b.computeOutgoingEdges();
        }
        clearPis(getInstructions());
    }

    private static void clearPis(SSAInstruction[] instructions)
    {
        for (int i = 0; i < instructions.length; i++)
        {
            if (instructions[i] instanceof SSAPiInstruction)
            {
                instructions[i] = null;
            }
        }
    }

    /** Create basic blocks for an empty method */
    private void makeEmptyBlocks()
    {
        BasicBlock b = new BasicBlock(-1);
        addNode(b);
    }

    protected BranchVisitor makeBranchVisitor(boolean[] r)
    {
        return new BranchVisitor(r);
    }

    protected PEIVisitor makePEIVisitor(boolean[] r)
    {
        return new PEIVisitor(r);
    }

    /** Walk through the instructions and compute basic block boundaries. */
    private void makeBasicBlocks()
    {
        SSAInstruction[] instructions = getInstructions();
        final boolean[] r = new boolean[instructions.length];

        // Compute r so r[i] == true iff instruction i begins a basic block.
        // While doing so count the number of blocks.
        r[0] = true;
        BranchVisitor branchVisitor = makeBranchVisitor(r);
        PEIVisitor peiVisitor = makePEIVisitor(r); // ### CHANGE ###
        for (int i = 0; i < instructions.length; i++)
        {
            if (instructions[i] != null)
            {
                branchVisitor.setIndex(i);
                instructions[i].visit(branchVisitor);
                // TODO: deal with exception handlers
                peiVisitor.setIndex(i); // ### CHANGE ###
                instructions[i].visit(peiVisitor); // ### CHANGE ###
            }
        }

        assert (instructions.length <= r.length);
        if (DEBUG)
        {
            System.err.println(
                    "Searching " + instructions.length + " instructions for basic clocks and Phi/Pi");
        }
        BasicBlock b = null;
        for (int i = 0; i < r.length; i++)
        {
            if (r[i])
            {
                b = new BasicBlock(i);
                addNode(b);
                int j = i;
                while (instructions[j] instanceof SSAPhiInstruction)
                {
                    b.addPhi((SSAPhiInstruction) instructions[j]);
                    j++;
                    if (j >= instructions.length)
                    {
                        break;
                    }
                }

                if (DEBUG)
                {
                    System.err.println(
                            ("Add basic block "
                                    + b.getNumber()
                                    + " (starting from "
                                    + b.getFirstInstructionIndex()
                                    + ") with instruction "
                                    + instructions[i]
                                    + " at aIndex "
                                    + i));
                }
            }
            if (instructions[i] instanceof SSAPiInstruction)
            {
                // add it to the current basic block
                b.addPi((SSAPiInstruction) instructions[i]);
            }
            i2block[i] = b;
        }
        // allocate the exit block
        BasicBlock exit = new BasicBlock(-1);
        if (DEBUG)
        {
            System.err.println(("Add exit block " + exit));
        }
        addNode(exit); // ### CHANGE ###
        clearPhis(instructions);
    }

    /** set to null any slots in the array with phi instructions */
    private static void clearPhis(SSAInstruction[] instructions)
    {
        for (int i = 0; i < instructions.length; i++)
        {
            if (instructions[i] instanceof SSAPhiInstruction)
            {
                instructions[i] = null;
            }
        }
    }

    /** This visitor identifies basic block boundaries induced by branch instructions. */
    public class BranchVisitor extends SSAInstruction.Visitor
    {
        private final boolean[] r;

        protected BranchVisitor(boolean[] r)
        {
            this.r = r;
        }

        int index = 0;

        void setIndex(int i)
        {
            index = i;
        }

        @Override
        public void visitGoto(SSAGotoInstruction instruction)
        {
            if (DEBUG)
            {
                System.err.println(
                        "Breaking Basic block after instruction " + instruction + " index " + index);
            }
            breakBasicBlock(index); // Breaks __after__ the GoTo-Instruction
            //final int jumpTarget = getIndexFromIIndexSkipPhi(instruction.getTarget());
            final int jumpTarget = instruction.getTarget();
            // assert (instructions[jumpTarget] != null) : "GoTo cant go to null";
            if (DEBUG)
            {
                System.err.println(
                        "Breaking Basic block before instruction "
                                + instructions[jumpTarget]
                                + " index "
                                + jumpTarget
                                + " -1");
            }
            breakBasicBlock(jumpTarget - 1); // Breaks __before__ the target
        }

        @Override
        public void visitConditionalBranch(SSAConditionalBranchInstruction instruction)
        {
            breakBasicBlock(instruction.getTarget() - 1); // Break at branch target
            breakBasicBlock(index);
        }

        @Override
        public void visitSwitch(SSASwitchInstruction instruction)
        {
            breakBasicBlock(index);
            int[] targets = instruction.getCasesAndLabels();
            for (int i = 1; i < targets.length; i += 2)
            {
                r[targets[i]] = true;
            }
        }

        @Override
        public void visitPhi(SSAPhiInstruction instruction)
        {
            // we can have more than one phi instruction in a row. break the basic block
            // only before the first one.
            if (!(instructions[index - 1] instanceof SSAPhiInstruction))
            {
                breakBasicBlock(index - 1);
            }
        }

        @Override
        public void visitReturn(SSAReturnInstruction instruction)
        {
            breakBasicBlock(index);
        }

        @Override
        public void visitThrow(SSAThrowInstruction instruction)
        {
            breakBasicBlock(index);
        }

        /**
         * introduce a basic block boundary immediately after instruction number 'index' if it is not
         * followed by pi instructions, or after the pi instructions otherwise
         */
        protected void breakBasicBlock(int index)
        {
            int j = index + 1;
            while (j < instructions.length && instructions[j] instanceof SSAPiInstruction)
            {
                j++;
            }
            if (j < instructions.length && !r[j])
            {
                r[j] = true;
            }
        }
    }

    // TODO: extend the following to deal with catch blocks. Right now
    // it simply breaks basic blocks at PEIs.
    public class PEIVisitor extends SSAInstruction.Visitor
    {
        private final boolean[] r;

        protected PEIVisitor(boolean[] r)
        {
            this.r = r;
        }

        int index = 0;

        void setIndex(int i)
        {
            index = i;
        }

        protected void breakBasicBlock()
        {
            int j = index + 1;
            while (j < instructions.length && instructions[j] instanceof SSAPiInstruction)
            {
                j++;
            }
            if (j < instructions.length && !r[j])
            {
                r[j] = true;
            }
        }

        @Override
        public void visitArrayLength(SSAArrayLengthInstruction instruction)
        {
            // breakBasicBlock(); // ### CHANGE ###
        }

        @Override
        public void visitArrayLoad(SSAArrayLoadInstruction instruction)
        {
            // breakBasicBlock(); // ### CHANGE ###
        }

        @Override
        public void visitArrayStore(SSAArrayStoreInstruction instruction)
        {
            // breakBasicBlock(); // ### CHANGE ###
        }

        @Override
        public void visitCheckCast(SSACheckCastInstruction instruction)
        {
            // breakBasicBlock(); // ### CHANGE ###
        }

        @Override
        public void visitGet(SSAGetInstruction instruction)
        {
            // breakBasicBlock(); // ### CHANGE ###
        }

        @Override
        public void visitInvoke(SSAInvokeInstruction instruction)
        {
            // breakBasicBlock(); // ### CHANGE ###
        }

        @Override
        public void visitMonitor(SSAMonitorInstruction instruction)
        {
            // breakBasicBlock(); // ### CHANGE ###
        }

        @Override
        public void visitNew(SSANewInstruction instruction)
        {
            // breakBasicBlock(); // ### CHANGE ###
        }

        @Override
        public void visitPut(SSAPutInstruction instruction)
        {
            // breakBasicBlock(); // ### CHANGE ###
        }

        @Override
        public void visitThrow(com.ibm.wala.ssa.SSAThrowInstruction instruction)
        {
            breakBasicBlock();
        }
    }

    @Override
    public BasicBlock getBlockForInstruction(int index)
    {
        if (i2block[index] == null)
        {
            Assertions.productionAssertion(false, "unexpected null for " + index);
        }
        return i2block[index];
    }

    // TODO: share some common parts of this implementation with the ShrikeCFG
    // implementation! right now it's clone-and-owned :(
    public class BasicBlock extends NodeWithNumber implements IBasicBlock<SSAInstruction>
    {

        private Collection<SSAPhiInstruction> phis;

        public Collection<SSAPhiInstruction> getPhis()
        {
            return phis == null
                    ? Collections.<SSAPhiInstruction>emptyList()
                    : Collections.unmodifiableCollection(phis);
        }

        public void addPhi(SSAPhiInstruction phiInstruction)
        {
            if (phis == null)
            {
                phis = new ArrayList<>(1);
            }
            phis.add(phiInstruction);
        }

        private ArrayList<SSAPiInstruction> pis;

        public Collection<SSAPiInstruction> getPis()
        {
            return pis == null
                    ? Collections.<SSAPiInstruction>emptyList()
                    : Collections.unmodifiableCollection(pis);
        }

        public void addPi(SSAPiInstruction piInstruction)
        {
            if (pis == null)
            {
                pis = new ArrayList<>(1);
            }
            pis.add(piInstruction);
        }

        @Override
        public boolean equals(Object arg0)
        {
            if (arg0 != null && getClass().equals(arg0.getClass()))
            {
                BasicBlock other = (BasicBlock) arg0;
                return start == other.start && getMethod().equals(other.getMethod());
            }
            else
            {
                return false;
            }
        }

        private final int start;

        BasicBlock(int start)
        {
            this.start = start;
        }

        /**
         * Add any exceptional edges generated by the last instruction in a basic block.
         *
         * @param last
         *            the last instruction in a basic block.
         */
        private void addExceptionalEdges(SSAInstruction last)
        {
            if (last == null)
            {
                // XXX: Bug here?
                // throw new IllegalStateException("Missing last SSA-Instruction in basic block (null).");
                // // XXX: When does this happen?
                // System.err.println("Missing last SSA-Instruction in basic block (null).");
                return;
            }
            if (last.isPEI())
            {
                // we don't currently model catch blocks here ... instead just link
                // to the exit block
                addExceptionalEdgeTo(exit());
            }
        }

        private void addNormalEdgeTo(BasicBlock b)
        {
            addNormalEdge(this, b);
        }

        private void addExceptionalEdgeTo(BasicBlock b)
        {
            addExceptionalEdge(this, b);
        }

        private void computeOutgoingEdges()
        {

            if (DEBUG)
            {
                System.err.println("Block " + this + ": computeOutgoingEdges()");
            }

            SSAInstruction last = getInstructions()[getLastInstructionIndex()];
            addExceptionalEdges(last); // ### CHANGE ###

            if (last instanceof SSAGotoInstruction)
            {
                int tgt = ((SSAGotoInstruction) last).getTarget();

                if (tgt != -1)
                {
                    int tgtNd = getIndexFromIIndexSkipPhi(tgt); // index in instructions-array, phi-skipped
                    BasicBlock target = null;

                    for (BasicBlock candid : InducedNoExceptionCFG.this)
                    {
                        if (Streams.stream(candid).filter(inst -> inst != null).anyMatch(inst -> inst.iIndex() == tgtNd)) // Since we skip the Phi index, the target may no longer
                                                                                                                          // at the beginning of the BB
                        // if (candid.getFirstInstructionIndex() == tgtNd)
                        {
                            target = candid;
                            break;
                        }
                    }

                    if (target == null)
                    {
                        System.err.println(
                                "Error retreiving the Node with IIndex " + tgt + " (in array at " + tgtNd + ')');
                        System.err.println(
                                "The associated Instruction "
                                        + instructions[tgtNd]
                                        + " does not start a basic block");
                        assert false; // It will fail anyway
                    }

                    if (DEBUG)
                    {
                        System.err.println(
                                "GOTO: Add additional CF " + last.iIndex() + " to " + tgt + " is node " + target);
                    }
                    addNormalEdgeTo(target);
                }
            }

            int normalSuccNodeNumber = getGraphNodeId() + 1;
            if ((last == null) || (last.isFallThrough())) // last == null means that last is a Phi, we automatically fallthrough to avoid problems
            {
                if (DEBUG)
                {
                    System.err.println(("Add fallthru to " + getNode(getGraphNodeId() + 1)));
                }
                addNormalEdgeTo(getNode(normalSuccNodeNumber));
            }

            if (last instanceof SSAGotoInstruction)
            {
                addNormalEdgeTo(getBlockForInstruction(((SSAGotoInstruction) last).getTarget()));
            }
            else if (last instanceof SSAConditionalBranchInstruction)
            {
                addNormalEdgeTo(
                        getBlockForInstruction(((SSAConditionalBranchInstruction) last).getTarget()));
            }
            else if (last instanceof SSASwitchInstruction)
            {
                int[] targets = ((SSASwitchInstruction) last).getCasesAndLabels();
                for (int i = 1; i < targets.length; i += 2)
                {
                    addNormalEdgeTo(getBlockForInstruction(targets[i]));
                }
            }

            if (pis != null)
            {
                updatePiInstrs(normalSuccNodeNumber);
            }

            if (last instanceof SSAReturnInstruction)
            {
                // link each return instrution to the exit block.
                BasicBlock exit = exit();
                addNormalEdgeTo(exit); // ### CHANGE ###
            }
        }

        /**
         * correct pi instructions with appropriate basic block numbers. we assume for now that pi
         * instructions are always associated with the normal "fall-thru" exit edge.
         */
        private void updatePiInstrs(int normalSuccNodeNumber)
        {
            for (int i = 0; i < pis.size(); i++)
            {
                SSAPiInstruction pi = pis.get(i);
                SSAInstructionFactory insts = getMethod().getDeclaringClass().getClassLoader().getInstructionFactory();
                pis.set(
                        i,
                        insts.PiInstruction(
                                SSAInstruction.NO_INDEX,
                                pi.getDef(),
                                pi.getVal(),
                                getGraphNodeId(),
                                normalSuccNodeNumber,
                                pi.getCause()));
            }
        }

        @Override
        public int getFirstInstructionIndex()
        {
            return start;
        }

        @Override
        public int getLastInstructionIndex()
        {
            int exitNumber = InducedNoExceptionCFG.this.getNumber(exit());
            if (getGraphNodeId() == exitNumber)
            {
                // this is the exit block
                return -2;
            }
            if (getGraphNodeId() == (exitNumber - 1))
            {
                // this is the last non-exit block
                return getInstructions().length - 1;
            }
            else
            {
                BasicBlock next = getNode(getGraphNodeId() + 1);
                return next.getFirstInstructionIndex() - 1;
            }
        }

        @Override
        public boolean isCatchBlock()
        {
            // TODO: support induced CFG with catch blocks.
            return false;
        }

        @Override
        public int hashCode()
        {
            return 1153 * getGraphNodeId() + getMethod().hashCode();
        }

        /** @see java.lang.Object#toString() */
        @Override
        public String toString()
        {
            return "BB[Induced]" + getNumber() + " - " + getMethod().getSignature();
        }

        @Override
        public boolean isExitBlock()
        {
            return getLastInstructionIndex() == -2;
        }

        @Override
        public boolean isEntryBlock()
        {
            return getNumber() == 0;
        }

        @Override
        public IMethod getMethod()
        {
            return InducedNoExceptionCFG.this.getMethod();
        }

        public boolean endsInPEI()
        {
            return getInstructions()[getLastInstructionIndex()].isPEI();
        }

        public boolean endsInReturn()
        {
            return getInstructions()[getLastInstructionIndex()] instanceof SSAReturnInstruction;
        }

        @Override
        public int getNumber()
        {
            return InducedNoExceptionCFG.this.getNumber(this);
        }

        @Override
        public Iterator<SSAInstruction> iterator()
        {
            if (getFirstInstructionIndex() <= getLastInstructionIndex())
            {
                return new ArrayIterator<>(
                        getInstructions(), getFirstInstructionIndex(), getLastInstructionIndex());
            }
            else
            {
                return Collections.emptyIterator();
            }

        }
    }

    @Override
    public String toString()
    {
        StringBuilder s = new StringBuilder();
        for (BasicBlock bb : this)
        {
            s.append("BB").append(getNumber(bb)).append('\n');
            for (int j = bb.getFirstInstructionIndex(); j <= bb.getLastInstructionIndex(); j++)
            {
                s.append("  ").append(j).append("  ").append(getInstructions()[j]).append('\n');
            }

            Iterator<BasicBlock> succNodes = getSuccNodes(bb);
            while (succNodes.hasNext())
            {
                s.append("    -> BB").append(getNumber(succNodes.next())).append('\n');
            }
        }
        return s.toString();
    }

    /**
     * Since this CFG is synthetic, for now we assume the instruction index is the same as the program
     * counter
     *
     * @see com.ibm.wala.cfg.ControlFlowGraph#getProgramCounter(int)
     */
    @Override
    public int getProgramCounter(int index)
    {
        if (getInstructions().length <= index)
        {
            throw new IllegalArgumentException("invalid index " + index + ' ' + getInstructions().length);
        }
        if (getInstructions()[index] instanceof SSAInvokeInstruction)
        {
            return ((SSAInvokeInstruction) getInstructions()[index]).getCallSite().getProgramCounter();
        }
        else
        {
            return index;
        }
    }

    /**
     * Get the position of a instruction with a given iindex in the internal list.
     *
     * @param iindex
     *            The iindex used when generating the SSAInstruction
     * @return index into the internal list of instructions
     * @throws IllegalStateException
     *             if no instruction exists with iindex or it's not in the internal
     *             array (Phi)
     */
    public int getIndexFromIIndex(int iindex)
    {
        if (iindex <= 0)
        {
            throw new IllegalArgumentException(
                    "The iindex may not be negative (is "
                            + iindex
                            + ". Method: "
                            + getMethod()
                            + ", Contenxt: "
                            + this.context);
        }

        final SSAInstruction[] instructions = getInstructions();
        if (instructions == null)
        {
            throw new IllegalStateException(
                    "This CFG contains no Instructions? " + getMethod() + ", Contenxt: " + this.context);
        }
        for (int i = 0; i < instructions.length; ++i)
        {
            if (instructions[i] == null)
            {
                // There are holes in the instructions array ?!
                // Perhaps from Phi-functions?
                if (DEBUG)
                {
                    System.err.println("The " + i + "th instrction is null! Mathod: " + getMethod());
                    if (i > 0)
                    {
                        System.err.println("  Instuction before is: " + instructions[i - 1]);
                    }
                    if (i < instructions.length - 1)
                    {
                        System.err.println("  Instuction after is: " + instructions[i + 1]);
                    }
                }
                continue;
            }
            if (instructions[i].iIndex() == iindex)
            {
                return i;
            }
        }

        throw new IllegalStateException(
                "The searched iindex ("
                        + iindex
                        + ") does not exist! In "
                        + getMethod()
                        + ", Contenxt: "
                        + this.context);
    }

    // Ugly function for SSAGotoInstruction
    public int getIndexFromIIndexSkipPhi(int iindex)
    {
        // if (iindex <= 0)
        if (iindex < 0)  // The first insturction (idx 0) can be a Phi, we just continue the search
        {
            throw new IllegalArgumentException(
                    "The iindex may not be negative (is "
                            + iindex
                            + ". Method: "
                            + getMethod()
                            + ", Contenxt: "
                            + this.context);
        }

        final SSAInstruction[] instructions = getInstructions();
        if (instructions == null)
        {
            throw new IllegalStateException(
                    "This CFG contains no Instructions? " + getMethod() + ", Contenxt: " + this.context);
        }
        int i = iindex;
        boolean skipPhi = false;
        while (i < instructions.length)
        {
            // If the goto target is null (Phi insturction), then continue to the next non-null instruction as the target
            if ((instructions[i] == null) && !skipPhi)
            {
                skipPhi = true;
                i++;
            }
            else if (instructions[i] != null & skipPhi)
            {
                return i;
            }
            else if (instructions[i] == null && skipPhi)
            {
                i++;
            }
            else
            {
                if (instructions[i].iIndex() == iindex)
                {
                    return i;
                }
            }
        }

        throw new IllegalStateException(
                "The searched iindex ("
                        + iindex
                        + ") does not exist! In "
                        + getMethod()
                        + ", Contenxt: "
                        + this.context);
    }

    public Collection<SSAPhiInstruction> getAllPhiInstructions()
    {
        Collection<SSAPhiInstruction> result = HashSetFactory.make();
        for (BasicBlock b : this)
        {
            result.addAll(b.getPhis());
        }
        return result;
    }
}
