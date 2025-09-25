package pfl.util;

import com.ibm.wala.util.NullProgressMonitor;

public class ProgressMonitor extends NullProgressMonitor
{
    private String task;
    private int totalWork;

    @Override
    public void beginTask(String task, int totalWork)
    {
        this.task = task;
        this.totalWork = totalWork;
        System.out.println("Begin Task: " + task + " Total Work: " + totalWork);
    }

    @Override
    public void worked(int units)
    {
        System.out.println("Task: " + task + " " + units + "/" + totalWork);
    }
}
